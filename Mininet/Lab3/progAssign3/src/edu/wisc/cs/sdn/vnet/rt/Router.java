package edu.wisc.cs.sdn.vnet.rt;

import java.util.Map;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import java.lang.Math;

import java.nio.ByteBuffer;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.MACAddress;
import net.floodlightcontroller.packet.ICMP;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;
import net.floodlightcontroller.packet.UDP;


/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 * @author modified by SiriusNEO
 */
public class Router extends Device {
	/** Routing table for the router */
	private RouteTable routeTable;

	/** ARP cache for the router */
	private ArpCache arpCache;

	/** ARP wait list for every IP for which we are waiting for the corresponding MAC address */
	private Map<Integer, ArrayList<Ethernet>> arpWaitLists;

	class RIPPair {
		RIPv2Entry entry;
		Long timestamp;

		RIPPair(RIPv2Entry entry, Long timestamp) {
			this.entry = entry;
			this.timestamp = timestamp;
		}
	}

	/** RIP entries (distance vector) */
	private Map<Integer, RIPPair> ripEntries;

	private boolean runRipFlag = false;

	private final String BROADCAST_MAC_ADDR = "FF:FF:FF:FF:FF:FF";
	private final String RIP_MULTICAST_IP = "224.0.0.9";

	private final Long RIP_TIMEOUT = (long)30000;
	private final Long RIP_PERSISTENT_TIMESTAMP = (long)-1;
	private final int RIP_METRIC_INF = 16; // RFC2453

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile) {
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
		this.arpWaitLists = new ConcurrentHashMap<>();
		this.ripEntries = new ConcurrentHashMap<>();
	}

	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable() {
		return this.routeTable;
	}

	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile) {
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}

		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}

	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile) {
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}

		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * When the route table is not specified, use method to initialize the route table.
	 * It will add entries to the route table for the subnets that are directly reachable
	 * via the router’s interfaces.
	 */
	public void startRip() {
		for (Iface iface : this.interfaces.values()) {
			int subnetMask = iface.getSubnetMask();
			int subnetIp = iface.getIpAddress() & subnetMask;
			if (routeTable.lookup(subnetIp) == null) {
				routeTable.insert(subnetIp, 0, subnetMask, iface);
				ripEntries.put(subnetIp,
					new RIPPair(new RIPv2Entry(subnetIp, subnetMask, 0), RIP_PERSISTENT_TIMESTAMP)
				);
			}
		}
		this.runRipFlag = true;

		System.out.println("No static route table. Initialize route table by directly reachable subnets.");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");

		for (Iface iface : this.interfaces.values()) {
			sendRipPacket(RIPv2.COMMAND_REQUEST, null, iface, false);
		}

		Timer timer = new Timer(true);

		// unsolicited RIP response
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				for (Iface iface : interfaces.values()) {
					sendRipPacket(RIPv2.COMMAND_RESPONSE, null, iface, true);
				}
			}
		}, 10000, 10000);

		// time out route table entries
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized(ripEntries) {
					Long curTime = System.currentTimeMillis();
					for (RIPPair pair: ripEntries.values()) {
						if (pair.timestamp != RIP_PERSISTENT_TIMESTAMP && curTime - pair.timestamp >= RIP_TIMEOUT) {
							ripEntries.remove(pair.entry.getAddress() & pair.entry.getSubnetMask());
							routeTable.remove(pair.entry.getAddress(), pair.entry.getSubnetMask());
							System.out.println("[RIP] timeout: " + IPv4.fromIPv4Address(pair.entry.getAddress()));
							showUpdatedRouteTable();
						}
					}
				}
			}
		}, 0, 100);

		// show distance vector
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				synchronized(ripEntries) {
					System.out.println("Show route entries.");
					System.out.println("-------------------------------------------------");
					for (RIPPair pair: ripEntries.values()) {
						System.out.println(pair.entry.toString() + " timestamp: " + String.valueOf(pair.timestamp));
					}
					System.out.println("-------------------------------------------------");
				}
			}
		}, 13000, 13000);

		System.out.println("RIP started. Two tasks are created.");
	}

	private void showUpdatedRouteTable() {
		System.out.println("Route table updated. Show new route table.");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface) {
		// System.out.println("*** -> Received packet: " +
        //         etherPacket.toString().replace("\n", "\n\t"));

		/********************************************************************/
		/* TODO: Handle packets                                             */

		switch(etherPacket.getEtherType())
		{
			case Ethernet.TYPE_IPv4:
				this.handleIpPacket(etherPacket, inIface);
				break;
			case Ethernet.TYPE_ARP:
				this.handleArpPacket(etherPacket, inIface);
				break;
			// Ignore all other packet types, for now
		}

		/********************************************************************/
	}

	private static int bytesToInt(byte[] bytes) {
		return ByteBuffer.wrap(bytes).getInt();
	}

	private void handleIpPacket(Ethernet etherPacket, Iface inIface) {
		// Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) {
			System.out.println("[IP Error] not a IPv4 packet");
			return;
		}

		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		if (runRipFlag &&
			ipPacket.getDestinationAddress() == IPv4.toIPv4Address(RIP_MULTICAST_IP) &&
			ipPacket.getProtocol() == IPv4.PROTOCOL_UDP) {
			UDP udpPacket = (UDP) ipPacket.getPayload();
			if (udpPacket.getSourcePort() == UDP.RIP_PORT && udpPacket.getDestinationPort() == UDP.RIP_PORT) {
				handleRipPacket(etherPacket, ipPacket, udpPacket, inIface);
				return;
			}
		}

        System.out.println("Handle IP packet");

        // Verify checksum
        short origCksum = ipPacket.getChecksum();
        ipPacket.resetChecksum();
        byte[] serialized = ipPacket.serialize();
        ipPacket.deserialize(serialized, 0, serialized.length);
        short calcCksum = ipPacket.getChecksum();
        if (origCksum != calcCksum) {
			System.out.println("[IP Error] checksum failed");
			return;
		}

        // Check TTL
        ipPacket.setTtl((byte)(ipPacket.getTtl()-1));
        if (0 == ipPacket.getTtl()) {
			this.sendIcmpPacket((byte)11, (byte)0, ipPacket, inIface); // time exceed message
			return;
		}

        // Reset checksum now that TTL is decremented
        ipPacket.resetChecksum();

        // Check if packet is destined for one of router's interfaces
        for (Iface iface: this.interfaces.values()) {
			if (iface.getIpAddress() == ipPacket.getDestinationAddress()) {
				// destination port unreachable
				if (ipPacket.getProtocol() == IPv4.PROTOCOL_TCP || ipPacket.getProtocol() == IPv4.PROTOCOL_UDP) {
					this.sendIcmpPacket((byte)3, (byte)3, ipPacket, inIface);
				}
				// echo reply
				else if (ipPacket.getProtocol() == IPv4.PROTOCOL_ICMP) {
					ICMP icmp = (ICMP) ipPacket.getPayload();
					if (icmp.getIcmpType() == (byte)8) { // echo request
						this.sendIcmpPacket((byte)0, (byte)0, ipPacket, inIface);
					}
				}
				return;
			}
		}

        // Do route lookup and forward
        this.forwardIpPacket(etherPacket, inIface);
	}

	private void handleArpPacket(Ethernet etherPacket, Iface inIface) {
		if (etherPacket.getEtherType() != Ethernet.TYPE_ARP) {
			System.out.println("[ARP Error] not a ARP packet");
			return;
		}

		ARP arpPacket = (ARP) etherPacket.getPayload();

		if (arpPacket.getOpCode() == ARP.OP_REQUEST) {
			int targetIp = bytesToInt(arpPacket.getTargetProtocolAddress());
			if (targetIp == inIface.getIpAddress()) {
				// target IP address equals the IP address of the interface
				this.sendArpReply(etherPacket.getSourceMAC(), arpPacket, inIface);
			}
		}
		else if (arpPacket.getOpCode() == ARP.OP_REPLY) {
			int queryIp = bytesToInt(arpPacket.getSenderProtocolAddress());
			byte[] queryMac = arpPacket.getSenderHardwareAddress();
			arpCache.insert(MACAddress.valueOf(arpPacket.getSenderHardwareAddress()), queryIp);

			synchronized(arpWaitLists) {
				ArrayList<Ethernet> waitList = arpWaitLists.get(queryIp);
				if (waitList  == null) {
					System.out.println("[ARP Error] receive a ARP Reply but not found in WaitList.");
					return;
				}
				for (Ethernet ether: waitList) {
					ether.setDestinationMACAddress(queryMac);
					this.sendPacket(ether, inIface);
				}
			}
		}
	}

	private void handleRipPacket(Ethernet etherPacket, IPv4 ipPacket, UDP udpPacket, Iface inIface) {
		// no need to check again...
		RIPv2 ripPacket = (RIPv2) udpPacket.getPayload();
		if (ripPacket.getCommand() == RIPv2.COMMAND_REQUEST) {
			System.out.println("RIP Request from: " + IPv4.fromIPv4Address(ipPacket.getSourceAddress()));
			sendRipPacket(RIPv2.COMMAND_RESPONSE, etherPacket, inIface, false);
		}
		else if (ripPacket.getCommand() == RIPv2.COMMAND_RESPONSE) {
			System.out.println("RIP Response from: " + IPv4.fromIPv4Address(ipPacket.getSourceAddress()));
			Long curTime = System.currentTimeMillis();
			for (RIPv2Entry entry: ripPacket.getEntries()) {
				int newMetric = Math.min(entry.getMetric()+1, RIP_METRIC_INF);
				int subnetIp = entry.getAddress() & entry.getSubnetMask();
				boolean rootTableUpdated = false;

				synchronized(ripEntries) {
					if (ripEntries.containsKey(subnetIp)) {
						RIPPair pair = ripEntries.get(subnetIp);

						if (pair.timestamp != RIP_PERSISTENT_TIMESTAMP && newMetric <= pair.entry.getMetric()) {
							pair.timestamp = curTime;
						}

						if (newMetric < RIP_METRIC_INF) {
							if (newMetric < pair.entry.getMetric()) {
								pair.entry.setMetric(newMetric);
								routeTable.update(entry.getAddress(), entry.getSubnetMask(), ipPacket.getSourceAddress(), inIface);
								rootTableUpdated = true;
								System.out.println("[RIP] update routeTable: " + IPv4.fromIPv4Address(entry.getAddress()));
							}
						}
						else {
							RouteEntry bestMatch = routeTable.lookup(entry.getAddress());
							if (bestMatch != null && bestMatch.getInterface() == inIface) {
								// poisoned reverse
								pair.entry.setMetric(newMetric);
								routeTable.remove(entry.getAddress(), entry.getSubnetMask());
								rootTableUpdated = true;
							}
						}
					}
					else {
						ripEntries.put(subnetIp, new RIPPair(
							new RIPv2Entry(entry.getAddress(), entry.getSubnetMask(), newMetric),
							curTime
						));
						if (newMetric < RIP_METRIC_INF) {
							routeTable.insert(entry.getAddress(), ipPacket.getSourceAddress(), entry.getSubnetMask(), inIface);
							rootTableUpdated = true;
							System.out.println("[RIP] insert routeTable: " + IPv4.fromIPv4Address(entry.getAddress()));
						}
					}
				}

				if (rootTableUpdated) {
					showUpdatedRouteTable();
				}
			}
		}
	}

	private static ARP makeArpBase(Iface inIface) {
		return new ARP().setHardwareType(ARP.HW_TYPE_ETHERNET)
						.setProtocolType(ARP.PROTO_TYPE_IP)
						.setHardwareAddressLength((byte)Ethernet.DATALAYER_ADDRESS_LENGTH)
						.setProtocolAddressLength((byte)4)
						.setSenderHardwareAddress(inIface.getMacAddress().toBytes())
						.setSenderProtocolAddress(inIface.getIpAddress());
	}

	private void sendArpReply(MACAddress origSourceMac, ARP origArpPacket, Iface inIface) {
		System.out.println("Send ARP Reply.");

		// making Ethernet Packet
		Ethernet ether = new Ethernet();
		ether.setEtherType(Ethernet.TYPE_ARP);
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(origSourceMac.toBytes());

		// making ARP header
		ARP arpReply = makeArpBase(inIface)
					.setOpCode(ARP.OP_REPLY)
					.setTargetHardwareAddress(origArpPacket.getSenderHardwareAddress())
					.setTargetProtocolAddress(origArpPacket.getSenderProtocolAddress());

		ether.setPayload(arpReply);
		arpReply.resetChecksum();
		ether.resetChecksum();

		this.sendPacket(ether, inIface);
	}

	private void sendArpRequest(int queryIp, Iface inIface, Iface outIface) {
		System.out.println("Send ARP Request.");

		// making Ethernet Packet
		Ethernet ether = new Ethernet();
		ether.setEtherType(Ethernet.TYPE_ARP);
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(BROADCAST_MAC_ADDR);

		// making ARP header
		ARP arpRequest = makeArpBase(inIface)
						.setOpCode(ARP.OP_REQUEST)
						.setTargetHardwareAddress(MACAddress.valueOf(0).toBytes())
						.setTargetProtocolAddress(queryIp);

		ether.setPayload(arpRequest);
		arpRequest.resetChecksum();
		ether.resetChecksum();

		this.sendPacket(ether, outIface);
	}

	/**
	 * A wrapper of the necessary information in ArpSendTask.
	 * (Since TimerTask.run can not use local variable)
	 */
	private void arpTaskWithContext(final int nextHop, final IPv4 ipPacket, final Iface inIface, final Iface outIface) {
		new Timer().schedule(
			new TimerTask() {
				int sentCounter = 0;
				@Override
				public void run() {
					if (arpCache.lookup(nextHop) != null) {
						this.cancel();
						return;
					}

					if (sentCounter == 3) {
						// 1 sec after third sent
						sendIcmpPacket((byte)3, (byte)1, ipPacket, inIface);
						arpWaitLists.remove(nextHop);
						this.cancel();
					}
					else {
						sendArpRequest(nextHop, inIface, outIface);
						sentCounter ++;
					}
				}
			}
		, 0, 1000); // every 1 second)
	}

	private void sendIcmpPacket(byte icmpType, byte icmpCode, IPv4 ipPacket, Iface inIface) {
		System.out.println("Send ICMP Packet. Type: " + String.valueOf(icmpType) + " Code: " + String.valueOf(icmpCode));

		// making Ethernet Packet
		Ethernet ether = new Ethernet();
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
		RouteEntry bestMatch = this.routeTable.lookup(ipPacket.getSourceAddress());
		if (null == bestMatch) {
			System.out.println("[ICMP Error] bestMatch == null");
			return;
		}
		int nextHop = (bestMatch.getGatewayAddress() == 0) ? ipPacket.getSourceAddress() : bestMatch.getGatewayAddress();
		ArpEntry arpEntry = this.arpCache.lookup(nextHop);
        if (null == arpEntry) {
			System.out.println("[ICMP Error] argEntry == null");
			return;
		}
		ether.setDestinationMACAddress(arpEntry.getMac().toBytes());

		// making IP Packet
		IPv4 ip = new IPv4();
		ip.setTtl((byte)64);
		ip.setProtocol(IPv4.PROTOCOL_ICMP);

		if (icmpType == 0) { // echo reply
			ip.setSourceAddress(ipPacket.getDestinationAddress());
		}
		else {
			ip.setSourceAddress(inIface.getIpAddress());
		}
		ip.setDestinationAddress(ipPacket.getSourceAddress());

		ICMP icmp = new ICMP();
		icmp.setIcmpType(icmpType); // 11: time exceed
		icmp.setIcmpCode(icmpCode);

		Data data = new Data();
		if (icmpType == 0) { // echo reply
			// payload of icmp request
			data.setData(ipPacket.getPayload().getPayload().serialize());
		}
		else {
			ipPacket.setChecksum((byte)0);
			byte[] originIpData = ipPacket.serialize();
			byte[] icmpPayloadData = new byte[4 + ipPacket.getHeaderLength()*4 + 8];
			for (int i = 0; i < 4; ++i) icmpPayloadData[i] = 0;
			for (int i = 0; i < ipPacket.getHeaderLength()*4 + 8; ++i)
				icmpPayloadData[i+4] = originIpData[i];
			data.setData(icmpPayloadData);
		}

		ether.setPayload(ip);
		ip.setPayload(icmp);
		icmp.setPayload(data);

		icmp.resetChecksum();
		ip.resetChecksum();
		ether.resetChecksum();

		this.sendPacket(ether, inIface);
	}

	private void sendRipPacket(byte command, Ethernet origEther, Iface outIface, boolean unsolicited) {
		System.out.println("Send RIP Packet. Command: " + String.valueOf(command));

		// making Ethernet Packet
		Ethernet ether = new Ethernet();
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setSourceMACAddress(outIface.getMacAddress().toBytes());

		// making ip
		IPv4 ip = new IPv4();
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setTtl((byte)64);
		ip.setSourceAddress(outIface.getIpAddress());

		// making udp
		UDP udp = new UDP();
		udp.setSourcePort(UDP.RIP_PORT);
		udp.setDestinationPort(UDP.RIP_PORT);

		// making rip
		RIPv2 rip = new RIPv2();
		rip.setCommand(command);

		synchronized(ripEntries) {
			for (RIPPair pair: ripEntries.values()) {
				rip.addEntry(new RIPv2Entry(pair.entry.getAddress(), pair.entry.getSubnetMask(), pair.entry.getMetric()));
			}
		}

		// select by type
		if (command == RIPv2.COMMAND_REQUEST || unsolicited) {
			ip.setDestinationAddress(RIP_MULTICAST_IP);
			ether.setDestinationMACAddress(BROADCAST_MAC_ADDR);
		}
		else {
			ip.setDestinationAddress(((IPv4)origEther.getPayload()).getSourceAddress());
			ether.setDestinationMACAddress(origEther.getSourceMACAddress());
		}

		udp.setPayload(rip);
		ip.setPayload(udp);
		ether.setPayload(ip);

		rip.resetChecksum();
		udp.resetChecksum();
		ip.resetChecksum();
		ether.resetChecksum();

		this.sendPacket(ether, outIface);
	}

    private void forwardIpPacket(Ethernet etherPacket, Iface inIface) {
        // Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }
        System.out.println("Forward IP packet");

		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
        int dstAddr = ipPacket.getDestinationAddress();

        // Find matching route table entry
        RouteEntry bestMatch = this.routeTable.lookup(dstAddr);

        // If no entry matched, destination net unreachable
        if (null == bestMatch) {
			this.sendIcmpPacket((byte)3, (byte)0, ipPacket, inIface);
			return;
		}

        // Make sure we don't sent a packet back out the interface it came in
        Iface outIface = bestMatch.getInterface();
        if (outIface == inIface) {
			// ...
			return;
		}

        // Set source MAC address in Ethernet header
        etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());

        // If no gateway, then nextHop is IP destination
        int nextHop = bestMatch.getGatewayAddress();
        if (0 == nextHop) {
			nextHop = dstAddr;
		}

        // Set destination MAC address in Ethernet header
        ArpEntry arpEntry = this.arpCache.lookup(nextHop);

		if (null == arpEntry) {
			// synchronized it
			synchronized(arpWaitLists) {
				if (!arpWaitLists.containsKey(nextHop)) {
					ArrayList<Ethernet> newWaitList = new ArrayList<Ethernet>();
					arpWaitLists.put(nextHop, newWaitList);
				}
				arpWaitLists.get(nextHop).add(etherPacket);
				arpTaskWithContext(nextHop, ipPacket, inIface, outIface);
			}
			return;
		}
        etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());

        this.sendPacket(etherPacket, outIface);
    }
}
