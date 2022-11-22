package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.Map.Entry;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

/**
 * @author Aaron Gember-Jacobson
 * @author modified by SiriusNEO
 */
public class Switch extends Device
{
	private static long TIMEOUT_MILISEC = 15 * 1000; // 15 sec

	private HashMap<MACAddress, Iface> macToIface;
	private HashMap<MACAddress, Long> macToTime;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.macToIface = new HashMap<MACAddress, Iface>();
		this.macToTime = new HashMap<MACAddress, Long>();
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
                etherPacket.toString().replace("\n", "\n\t"));

		/********************************************************************/
		/* TODO: Handle packets                                             */

		long curTime = System.currentTimeMillis();

		MACAddress srcMac = etherPacket.getSourceMAC(),
				dstMac = etherPacket.getDestinationMAC();

		// learn
		if (!this.macToIface.containsKey(srcMac)) {
			System.out.println(
				String.format("Learned address: %s -> %s", srcMac.toString(), inIface)
			);
			this.macToIface.put(srcMac, inIface);
		}
		this.macToTime.put(srcMac, curTime); // reset time

		// expire
		// the java version is so old that I can not use filter and removeAll...
		// note: there is no need to use a new thread to do expiration
		List<MACAddress> filtered = new ArrayList<MACAddress>();;
		for (Entry<MACAddress, Long> entry: this.macToTime.entrySet()) {
			if (curTime - entry.getValue() >= TIMEOUT_MILISEC) {
				filtered.add(entry.getKey());
			}
		}
		if (!filtered.isEmpty()) {
			System.out.println("Expired address in table: " + filtered.toString());
		}
		for (MACAddress each: filtered) {
			this.macToTime.remove(each);
		}

		// hit
		if (this.macToIface.containsKey(dstMac)) {
			Iface outIface = this.macToIface.get(dstMac);
			System.out.println(
				String.format("Mac hit in table! Forwarding: %s -> %s", inIface, outIface)
			);
			this.sendPacket(etherPacket, outIface);
		}
		// flooding
		else {
			System.out.println(
				String.format("Address: %s not found in table. Flooding.", dstMac.toString())
			);
			for (Iface iface: this.interfaces.values()) {
				if (iface != inIface) {
					this.sendPacket(etherPacket, iface);
				}
			}
		}

		/********************************************************************/
	}
}
