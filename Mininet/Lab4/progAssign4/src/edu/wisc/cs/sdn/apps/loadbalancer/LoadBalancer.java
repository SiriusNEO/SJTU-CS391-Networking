package edu.wisc.cs.sdn.apps.loadbalancer;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFOXMFieldType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionSetField;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.openflow.protocol.instruction.OFInstructionGotoTable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.wisc.cs.sdn.apps.util.ArpServer;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;
import edu.wisc.cs.sdn.apps.l3routing.L3Routing;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.internal.DeviceManagerImpl;

import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.util.MACAddress;

public class LoadBalancer implements IFloodlightModule, IOFSwitchListener,
		IOFMessageListener
{
	public static final String MODULE_NAME = LoadBalancer.class.getSimpleName();

	private static final byte TCP_FLAG_SYN = 0x02;

	private static final short IDLE_TIMEOUT = 20;

	// Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);

    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;

    // Interface to device manager service
    private IDeviceService deviceProv;

    // Switch table in which rules should be installed
    private byte table;

    // Set of virtual IPs and the load balancer instances they correspond with
    private Map<Integer,LoadBalancerInstance> instances;

    /**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));

		// Obtain table number from config
		Map<String,String> config = context.getConfigParams(this);
        this.table = Byte.parseByte(config.get("table"));

        // Create instances from config
        this.instances = new HashMap<Integer,LoadBalancerInstance>();
        String[] instanceConfigs = config.get("instances").split(";");
        for (String instanceConfig : instanceConfigs)
        {
        	String[] configItems = instanceConfig.split(" ");
        	if (configItems.length != 3)
        	{
        		log.error("Ignoring bad instance config: " + instanceConfig);
        		continue;
        	}
        	LoadBalancerInstance instance = new LoadBalancerInstance(
        			configItems[0], configItems[1], configItems[2].split(","));
            this.instances.put(instance.getVirtualIP(), instance);
            log.info("Added load balancer instance: " + instance);
        }

		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);

        /*********************************************************************/
        /* TODO: Initialize other class variables, if necessary              */

        // nothing

        /*********************************************************************/
	}

	/**
     * Subscribes to events and performs other startup tasks.
     */
	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException
	{
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.floodlightProv.addOFMessageListener(OFType.PACKET_IN, this);

		/*********************************************************************/
		/* TODO: Perform other tasks, if necessary                           */

        // nothing

		/*********************************************************************/
	}

	/**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override
	public void switchAdded(long switchId)
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));

		/*********************************************************************/
		/* TODO: Install rules to send:                                      */
		/*       (1) packets from new connections to each virtual load       */
		/*       balancer IP to the controller                               */
		/*       (2) ARP packets to the controller, and                      */
		/*       (3) all other packets to the next rule table in the switch  */

        // (1)
        for (int vip: this.instances.keySet()) {
            installRuleByPort(
                sw,
                new OFMatch().setDataLayerType(OFMatch.ETH_TYPE_IPV4)
                             .setNetworkProtocol(OFMatch.IP_PROTO_TCP)
                             .setNetworkDestination(vip),
                OFPort.OFPP_CONTROLLER,
                SwitchCommands.DEFAULT_PRIORITY
            );
        }

        // (2)
        installRuleByPort(
            sw,
            new OFMatch().setDataLayerType(OFMatch.ETH_TYPE_ARP),
            OFPort.OFPP_CONTROLLER,
            SwitchCommands.DEFAULT_PRIORITY
        );

        // (3)
        // not use installRuleForSwitch since we should use OFInstructionGotoTable here
		SwitchCommands.installRule(
            sw,
            table,
            SwitchCommands.DEFAULT_PRIORITY,
			new OFMatch(),
            Arrays.asList(getDefaultInst())
        );

		/*********************************************************************/
	}

	/**
	 * Handle incoming packets sent from switches.
	 * @param sw switch on which the packet was received
	 * @param msg message from the switch
	 * @param cntx the Floodlight context in which the message should be handled
	 * @return indication whether another module should also process the packet
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx)
	{
		// We're only interested in packet-in messages
		if (msg.getType() != OFType.PACKET_IN)
		{ return Command.CONTINUE; }
		OFPacketIn pktIn = (OFPacketIn)msg;

		// Handle the packet
		Ethernet ethPkt = new Ethernet();
		ethPkt.deserialize(pktIn.getPacketData(), 0,
				pktIn.getPacketData().length);

		/*********************************************************************/
		/* TODO: Send an ARP reply for ARP requests for virtual IPs; for TCP */
		/*       SYNs sent to a virtual IP, select a host and install        */
		/*       connection-specific rules to rewrite IP and MAC addresses;  */
		/*       ignore all other packets                                    */

		/*********************************************************************/

        if (ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
            // Send an ARP reply
            ARP arpPacket = (ARP) ethPkt.getPayload();
            if (arpPacket.getOpCode() != ARP.OP_REQUEST) {
                // only for ARP request
                return Command.CONTINUE;
            }

            int vip = IPv4.toIPv4Address(arpPacket.getTargetProtocolAddress());
            if (!this.instances.containsKey(vip)) {
                // not found corresponding virtual IP in instances
                return Command.CONTINUE;
            }

            Ethernet arpReplyEther = makeArpReply(vip, ethPkt, arpPacket);
            SwitchCommands.sendPacket(sw, (short)pktIn.getInPort(), arpReplyEther);
        }
        else if (ethPkt.getEtherType() == Ethernet.TYPE_IPv4) {
            // Select a host and install connection-specific rules to rewrite IP and MAC addresses
            IPv4 ipPacket = (IPv4) ethPkt.getPayload();

            // Check: only for TCP
            if (ipPacket.getProtocol() != IPv4.PROTOCOL_TCP) {
		        return Command.CONTINUE;
            }

            TCP tcpPacket = (TCP) ipPacket.getPayload();
            // Check: only for TCP SYN
            if (tcpPacket.getFlags() != TCP_FLAG_SYN) {
		        return Command.CONTINUE;
            }

            int vip = ipPacket.getDestinationAddress();
            int nextIp = this.instances.get(vip).getNextHostIP();
            byte[] vMac = this.instances.get(vip).getVirtualMAC();
            byte[] nextMac = this.getHostMACAddress(nextIp);

            // Rewrite the destination IP and MAC address of TCP packets sent from a client to the virtual IP
            OFMatch cToVMatch = new OFMatch().setDataLayerType(Ethernet.TYPE_IPv4)
                                             .setNetworkSource(ipPacket.getSourceAddress())
                                             .setNetworkDestination(vip)
                                             .setNetworkProtocol(OFMatch.IP_PROTO_TCP)
                                             .setTransportSource(tcpPacket.getSourcePort())
                                             .setTransportDestination(tcpPacket.getDestinationPort());
            OFInstruction cToVInst = new OFInstructionApplyActions(
                Arrays.asList(
                    (OFAction) new OFActionSetField(OFOXMFieldType.IPV4_DST, nextIp),
                    (OFAction) new OFActionSetField(OFOXMFieldType.ETH_DST, nextMac)
                )
            );

            SwitchCommands.installRule(sw, table,
                (short)(SwitchCommands.DEFAULT_PRIORITY + 1), // higher priority
                cToVMatch,
                Arrays.asList(cToVInst, getDefaultInst()),
                SwitchCommands.NO_TIMEOUT, // no hard timeout
                IDLE_TIMEOUT // idle timeout of 20 seconds
            );


            // Rewrite the source IP and MAC address of TCP packets sent from server to client
            OFMatch vToCMatch = new OFMatch().setDataLayerType(Ethernet.TYPE_IPv4)
                                             .setNetworkSource(nextIp)
                                             .setNetworkDestination(ipPacket.getSourceAddress())
                                             .setNetworkProtocol(OFMatch.IP_PROTO_TCP)
                                             .setTransportSource(tcpPacket.getDestinationPort())
                                             .setTransportDestination(tcpPacket.getSourcePort());
            OFInstruction vToCInst = new OFInstructionApplyActions(
                Arrays.asList(
                    (OFAction) new OFActionSetField(OFOXMFieldType.IPV4_SRC, vip),
                    (OFAction) new OFActionSetField(OFOXMFieldType.ETH_SRC, vMac)
                )
            );

            SwitchCommands.installRule(sw, table,
                (short)(SwitchCommands.DEFAULT_PRIORITY + 1), // higher priority
                vToCMatch,
                Arrays.asList(vToCInst, getDefaultInst()),
                SwitchCommands.NO_TIMEOUT,
                IDLE_TIMEOUT
            );
        }

		// We don't care about other packets
		return Command.CONTINUE;
	}

	/**
	 * Returns the MAC address for a host, given the host's IP address.
	 * @param hostIPAddress the host's IP address
	 * @return the hosts's MAC address, null if unknown
	 */
	private byte[] getHostMACAddress(int hostIPAddress)
	{
		Iterator<? extends IDevice> iterator = this.deviceProv.queryDevices(
				null, null, hostIPAddress, null, null);
		if (!iterator.hasNext())
		{ return null; }
		IDevice device = iterator.next();
		return MACAddress.valueOf(device.getMACAddress()).toBytes();
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId)
	{ /* Nothing we need to do, since the switch is no longer active */ }

	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId)
	{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type)
	{ /* Nothing we need to do, since load balancer rules are port-agnostic */}

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId)
	{ /* Nothing we need to do */ }

    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices()
	{ return null; }

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService>
			getServiceImpls()
	{ return null; }

	/**
     * Tell the module system which modules we depend on.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>>
			getModuleDependencies()
	{
		Collection<Class<? extends IFloodlightService >> floodlightService =
	            new ArrayList<Class<? extends IFloodlightService>>();
        floodlightService.add(IFloodlightProviderService.class);
        floodlightService.add(IDeviceService.class);
        return floodlightService;
	}

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName()
	{ return MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name)
	{
		return (OFType.PACKET_IN == type
				&& (name.equals(ArpServer.MODULE_NAME)
					|| name.equals(DeviceManagerImpl.MODULE_NAME)));
	}

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name)
	{ return false; }

    static private OFInstruction getDefaultInst() {
        return new OFInstructionGotoTable(L3Routing.table);
    }

    private void installRuleByPort(IOFSwitch sw, OFMatch match, OFPort port, int priority) {
        SwitchCommands.installRule(sw, table, (short)priority, match,
        Arrays.asList(
            (OFInstruction) new OFInstructionApplyActions(
                Arrays.asList(
                    (OFAction) new OFActionOutput(port)
                )
            )
        ));
    }

	private Ethernet makeArpReply(int vip, Ethernet origEther, ARP origArp) {
		byte[] vMac = this.instances.get(vip).getVirtualMAC();

		// making Ethernet Packet
		Ethernet ether = new Ethernet();
		ether.setEtherType(Ethernet.TYPE_ARP);
		ether.setSourceMACAddress(vMac);
		ether.setDestinationMACAddress(origEther.getSourceMACAddress());

		// making ARP header
		ARP arpReply = new ARP()
                    .setHardwareType(ARP.HW_TYPE_ETHERNET)
                    .setProtocolType(ARP.PROTO_TYPE_IP)
                    .setHardwareAddressLength((byte)Ethernet.DATALAYER_ADDRESS_LENGTH)
                    .setProtocolAddressLength((byte)4)
                    .setSenderHardwareAddress(vMac)
                    .setSenderProtocolAddress(vip)
					.setOpCode(ARP.OP_REPLY)
					.setTargetHardwareAddress(origArp.getSenderHardwareAddress())
					.setTargetProtocolAddress(origArp.getSenderProtocolAddress());

		ether.setPayload(arpReply);
		arpReply.resetChecksum();
		ether.resetChecksum();

		return ether;
	}
}
