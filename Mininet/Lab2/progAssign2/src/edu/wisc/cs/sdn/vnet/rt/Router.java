package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 * @author modified by SiriusNEO
 */
public class Router extends Device
{
	/** Routing table for the router */
	private RouteTable routeTable;

	/** ARP cache for the router */
	private ArpCache arpCache;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}

	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }

	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
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
	public void loadArpCache(String arpCacheFile)
	{
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
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		// System.out.println("*** -> Received packet: " +
        //        etherPacket.toString().replace("\n", "\n\t"));

		/********************************************************************/
		/* TODO: Handle packets                                             */

		try {
			// check if the type is IPv4
			if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) {
				throw new Drop("packet type is not IPv4");
			}

			// check checksum
			IPv4 payload = (IPv4)etherPacket.getPayload();
			short checkSum = payload.getChecksum();
			payload.setChecksum((short)0);
			payload.serialize();
			if (payload.getChecksum() != checkSum) {
				throw new Drop(
					String.format("checksum is incorrect. Expected %d but got %d.", checkSum, payload.getChecksum())
				);
			}

			// check TTL
			payload.setTtl((byte)(payload.getTtl() - 1)); // decre by 1
			if (payload.getTtl() <= (byte)0) {
				throw new Drop("TTL wrong.");
			}
			payload.setChecksum((short)0);
			payload.serialize();

			// check whether the packet is destined for one of the router’s interfaces
			for (Iface iface: this.interfaces.values()) {
				if (iface.getIpAddress() == payload.getDestinationAddress()) {
					throw new Drop("the packet dst matches one of the interfaces.");
				}
			}

			// get forward entry
			RouteEntry forwardEntry = this.routeTable.lookup(payload.getDestinationAddress());
			if (forwardEntry == null) {
				throw new Drop("no entry matches");
			}
			Iface outgoing = forwardEntry.getInterface();

			// get MAC
			int gateway = forwardEntry.getGatewayAddress(),
				lookupIp = gateway != 0 ? gateway : payload.getDestinationAddress();
			ArpEntry arpEntry = this.arpCache.lookup(lookupIp);
			if (arpEntry == null) {
				throw new Drop("get MAC error for ip: " + IPv4.fromIPv4Address(lookupIp));
			}

			// send
			etherPacket.setSourceMACAddress(outgoing.getMacAddress().toBytes());
			etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());
			// System.out.println("new eth packet: " + etherPacket.toString());
			this.sendPacket(etherPacket, outgoing);
			System.out.println(String.format("Router send: %s -> %s", inIface.getName(), outgoing.getName()));
		} catch (Drop e) {
			// System.out.println(e.getMessage());
		}

		/********************************************************************/
	}
}

/**
 * use Drop Exception + try catch to elegantly handle the drop issue and print log info
 */
class Drop extends Exception {
	Drop(String msg) {super("Drop: " + msg);}
}
