package com.lukekorth.auto_fi.openvpn;

import android.content.Context;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler.Callback;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.support.annotation.MainThread;
import android.system.OsConstants;
import android.text.TextUtils;

import com.lukekorth.auto_fi.R;
import com.lukekorth.auto_fi.interfaces.Vpn;
import com.lukekorth.auto_fi.interfaces.VpnServiceInterface;
import com.lukekorth.auto_fi.network.CIDRIP;
import com.lukekorth.auto_fi.network.IPAddress;
import com.lukekorth.auto_fi.network.NetworkSpace;
import com.lukekorth.auto_fi.utilities.Logger;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class OpenVpn implements Vpn, Callback {

    private final List<String> mDNSList = new ArrayList<>();
    private final NetworkSpace mRoutes = new NetworkSpace();
    private final NetworkSpace mRoutesV6 = new NetworkSpace();

    private String mDomain = null;
    private CIDRIP mLocalIP = null;
    private int mMTU;
    private String mLocalIPv6 = null;
    private OpenVpnManagementThread mManagementThread;
    private String mLastTunConfig;
    private String mRemoteGateway;
    private Context mContext;
    private VpnServiceInterface mVpnService;

    public OpenVpn(Context context, VpnServiceInterface vpnServiceInterface) {
        mContext = context;
        mVpnService = vpnServiceInterface;
    }

    @MainThread
    @Override
    public void start() {
        mVpnService.setNotificationMessage(R.string.building_configuration);

        mManagementThread = new OpenVpnManagementThread(this);
        if (mManagementThread.openManagementConnection(mContext)) {
            new Thread(mManagementThread, "OpenVPNManagementThread").start();
            Logger.info("Started OpenVPN management thread");

            new Thread(new OpenVpnThread(mContext, this), "OpenVPNProcessThread").start();
        } else {
            stop();
        }
    }

    @MainThread
    @Override
    public void stop() {
        mManagementThread.stopVPN();
    }

    VpnServiceInterface getVpnService() {
        return mVpnService;
    }

    private String getTunConfigString() {
        // The format of the string is not important, only that
        // two identical configurations produce the same result
        String cfg = "TUNCFG UNQIUE STRING ips:";

        if (mLocalIP != null) {
            cfg += mLocalIP.toString();
        }
        if (mLocalIPv6 != null) {
            cfg += mLocalIPv6;
        }

        cfg += "routes: " + TextUtils.join("|", mRoutes.getNetworks(true)) + TextUtils.join("|", mRoutesV6.getNetworks(true));
        cfg += "excl. routes:" + TextUtils.join("|", mRoutes.getNetworks(false)) + TextUtils.join("|", mRoutesV6.getNetworks(false));
        cfg += "dns: " + TextUtils.join("|", mDNSList);
        cfg += "domain: " + mDomain;
        cfg += "mtu: " + mMTU;

        return cfg;
    }

    public ParcelFileDescriptor openTun() {
        Logger.info("Opening tun interface");

        VpnService.Builder builder = mVpnService.getBuilder();

        builder.allowFamily(OsConstants.AF_INET);
        builder.allowFamily(OsConstants.AF_INET6);

        if (mLocalIP == null && mLocalIPv6 == null) {
            Logger.error("Refusing to open tun device without IP information");
            return null;
        }

        if (mLocalIP != null) {
            addLocalNetworksToRoutes();
            try {
                builder.addAddress(mLocalIP.getIp(), mLocalIP.getLength());
            } catch (IllegalArgumentException iae) {
                Logger.error("Could not add DNS Server " + mLocalIP + ", rejected by the system: " + iae.getMessage());
                return null;
            }
        }

        if (mLocalIPv6 != null) {
            String[] ipv6parts = mLocalIPv6.split("/");
            try {
                builder.addAddress(ipv6parts[0], Integer.parseInt(ipv6parts[1]));
            } catch (IllegalArgumentException iae) {
                Logger.error("Could not configure IP Address " + mLocalIPv6 + ", rejected by the system: " + iae.getMessage());
                return null;
            }
        }

        for (String dns : mDNSList) {
            try {
                builder.addDnsServer(dns);
            } catch (IllegalArgumentException iae) {
                Logger.error("Could not add DNS Server " + dns + ", rejected by the system: " + iae.getMessage());
            }
        }

        String release = Build.VERSION.RELEASE;
        if ((Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT && !release.startsWith("4.4.3")
                && !release.startsWith("4.4.4") && !release.startsWith("4.4.5") && !release.startsWith("4.4.6"))
                && mMTU < 1280) {
            Logger.info("Forcing MTU to 1280 instead of " + mMTU + " to workaround Android Bug #70916");
            builder.setMtu(1280);
        } else {
            builder.setMtu(mMTU);
        }

        Collection<IPAddress> positiveIPv4Routes = mRoutes.getPositiveIPList();
        Collection<IPAddress> positiveIPv6Routes = mRoutesV6.getPositiveIPList();

        if ("samsung".equals(Build.BRAND) && mDNSList.size() >= 1) {
            // Check if the first DNS Server is in the VPN range
            try {
                IPAddress dnsServer = new IPAddress(new CIDRIP(mDNSList.get(0), 32), true);
                boolean dnsIncluded = false;
                for (IPAddress net : positiveIPv4Routes) {
                    if (net.containsNet(dnsServer)) {
                        dnsIncluded = true;
                    }
                }
                if (!dnsIncluded) {
                    Logger.warn("Warning Samsung Android 5.0+ devices ignore DNS servers outside " +
                            "the VPN range. To enable DNS resolution a route to your DNS Server " +
                            mDNSList.get(0) + " has been added");
                    positiveIPv4Routes.add(dnsServer);
                }
            } catch (Exception e) {
                Logger.error("Error parsing DNS Server IP: " + mDNSList.get(0));
            }
        }

        IPAddress multicastRange = new IPAddress(new CIDRIP("224.0.0.0", 3), true);

        for (IPAddress route : positiveIPv4Routes) {
            try {
                if (multicastRange.containsNet(route)) {
                    Logger.debug("Ignoring multicast route: " + route.toString());
                } else {
                    builder.addRoute(route.getIPv4Address(), route.getNetworkMask());
                }
            } catch (IllegalArgumentException ia) {
                Logger.error("Route rejected by Android: " + route + " " + ia.getMessage());
            }
        }

        for (IPAddress route6 : positiveIPv6Routes) {
            try {
                builder.addRoute(route6.getIPv6Address(), route6.getNetworkMask());
            } catch (IllegalArgumentException ia) {
                Logger.error("Route rejected by Android: " + route6 + " " + ia.getMessage());
            }
        }

        if (mDomain != null) {
            builder.addSearchDomain(mDomain);
        }

        Logger.info("Local IPv4: " + mLocalIP.getIp() + "/" + mLocalIP.getLength() + " IPv6: " + mLocalIPv6 + " MTU: " + mMTU);
        Logger.info("DNS Server: " + TextUtils.join(", ", mDNSList) + ", Domain: " + mDomain);
        Logger.info("Routes: " + TextUtils.join(", ", mRoutes.getNetworks(true)) + " " + TextUtils.join(", ", mRoutesV6.getNetworks(true)));
        Logger.info("Routes excluded: " + TextUtils.join(", ", mRoutes.getNetworks(false)) + " " + TextUtils.join(", ", mRoutesV6.getNetworks(false)));
        Logger.debug("VpnService routes installed: " + TextUtils.join(", ", positiveIPv4Routes) + " " + TextUtils.join(", ", positiveIPv6Routes));

        String session = "";
        if (mLocalIP != null && mLocalIPv6 != null) {
            session = mContext.getString(R.string.session_ipv6string, mLocalIP, mLocalIPv6);
        } else if (mLocalIP != null) {
            session = mContext.getString(R.string.session_ipv4string, mLocalIP);
        }

        builder.setSession(session);

        if (mDNSList.size() == 0) {
            Logger.info("No DNS servers being used. Name resolution may not work. Consider setting custom DNS " +
                    "Servers. Please also note that Android will keep using your proxy settings specified for your " +
                    "mobile/Wi-Fi connection when no DNS servers are set.");
        }

        mLastTunConfig = getTunConfigString();

        // Reset information
        mDNSList.clear();
        mRoutes.clear();
        mRoutesV6.clear();
        mLocalIP = null;
        mLocalIPv6 = null;
        mDomain = null;

        try {
            ParcelFileDescriptor tun = builder.establish();
            if (tun == null) {
                throw new NullPointerException("Android establish() method returned null (Really broken network configuration?)");
            }
            return tun;
        } catch (Exception e) {
            Logger.error("Failed to open the tun interface. " + e.getMessage());
            return null;
        }
    }

    private void addLocalNetworksToRoutes() {
        // Add local network interfaces
        String[] localRoutes = NativeMethods.getIfconfig();
        if (localRoutes == null) {
            return;
        }

        // The format of mLocalRoutes is kind of broken because I don't really like JNI
        for (int i = 0; i < localRoutes.length; i += 3) {
            String intf = localRoutes[i];
            String ipAddr = localRoutes[i + 1];
            String netMask = localRoutes[i + 2];

            if (intf == null || intf.equals("lo") || intf.startsWith("tun") || intf.startsWith("rmnet")) {
                continue;
            }

            if (ipAddr == null || netMask == null) {
                Logger.error("Local routes are broken. " + TextUtils.join("|", localRoutes));
                continue;
            }

            if (ipAddr.equals(mLocalIP.getIp())) {
                continue;
            }

            mRoutes.addIP(new CIDRIP(ipAddr, netMask), false);
        }
    }

    public void addDNS(String dns) {
        mDNSList.add(dns);
    }

    public void setDomain(String domain) {
        if (mDomain == null) {
            mDomain = domain;
        }
    }

    /**
     * Route that is always included, used by the v3 core
     */
    public void addRoute(CIDRIP route) {
        mRoutes.addIP(route, true);
    }

    public void addRoute(String dest, String mask, String gateway, String device) {
        CIDRIP route = new CIDRIP(dest, mask);
        boolean include = isAndroidTunDevice(device);

        IPAddress gatewayIP = new IPAddress(new CIDRIP(gateway, 32), false);

        if (mLocalIP == null) {
            Logger.error("Local IP address unset and received. Neither pushed server config nor " +
                    "local config specifies and IP address. Opening tun device will fail");
            return;
        }

        IPAddress localNet = new IPAddress(mLocalIP, true);
        if (localNet.containsNet(gatewayIP)) {
            include = true;
        }

        if (gateway != null && (gateway.equals("255.255.255.255") || gateway.equals(mRemoteGateway))) {
            include = true;
        }

        if (route.getLength() == 32 && !mask.equals("255.255.255.255")) {
            Logger.warn("Cannot make sense of " + dest + " and " + mask + " as IP route with CIDR netmask, " +
                    "using /32 as netmask.");
        }

        if (route.normalize()) {
            Logger.warn("Corrected route " + dest + "/" + route.getLength() + " to " + route.getLength() + "/" +
                    route.getLength());
        }

        mRoutes.addIP(route, include);
    }

    public void addRouteV6(String network, String device) {
        String[] v6parts = network.split("/");
        boolean included = isAndroidTunDevice(device);

        // Tun is opened after ROUTE6, no device name may be present
        try {
            Inet6Address ip = (Inet6Address) InetAddress.getAllByName(v6parts[0])[0];
            int mask = Integer.parseInt(v6parts[1]);
            mRoutesV6.addIPv6(ip, mask, included);
        } catch (UnknownHostException e) {
            Logger.error(e);
        }
    }

    private boolean isAndroidTunDevice(String device) {
        return device != null && (device.startsWith("tun") || "(null)".equals(device) || "vpnservice-tun".equals(device));
    }

    public void setMtu(int mtu) {
        mMTU = mtu;
    }

    public void setLocalIP(String local, String netmask, int mtu, String mode) {
        mLocalIP = new CIDRIP(local, netmask);
        mMTU = mtu;
        mRemoteGateway = null;

        long netMaskAsInt = CIDRIP.getInt(netmask);

        if (mLocalIP.getLength() == 32 && !netmask.equals("255.255.255.255")) {
            // get the netmask as IP
            int masklen;
            long mask;
            if ("net30".equals(mode)) {
                masklen = 30;
                mask = 0xfffffffc;
            } else {
                masklen = 31;
                mask = 0xfffffffe;
            }

            // Netmask is Ip address +/-1, assume net30/p2p with small net
            if ((netMaskAsInt & mask) == (mLocalIP.getInt() & mask)) {
                mLocalIP.setLength(masklen);
            } else {
                mLocalIP.setLength(32);
                if (!"p2p".equals(mode)) {
                    Logger.warn("Got interface information " + local + " and " + netmask + ", assuming second " +
                            "address is peer address of remote. Using /32 netmask for local IP. Mode given by " +
                            "OpenVPN is " + mode);
                }
            }
        }
        if (("p2p".equals(mode) && mLocalIP.getLength() < 32) || ("net30".equals(mode) && mLocalIP.getLength() < 30)) {
            Logger.warn("Vpn topology " + mode + " specified but ifconfig " + local + " " + netmask + " looks more " +
                    "like an IP address with a network mask. Assuming \"subnet\" topology.");
        }

        /* Workaround for Lollipop, it  does not route traffic to the VPNs own network mask */
        if (mLocalIP.getLength() <= 31) {
            CIDRIP interfaceRoute = new CIDRIP(mLocalIP.getIp(), mLocalIP.getIp());
            interfaceRoute.normalize();
            addRoute(interfaceRoute);
        }

        // Configurations are sometimes really broken...
        mRemoteGateway = netmask;
    }

    public void setLocalIPv6(String ipv6addr) {
        mLocalIPv6 = ipv6addr;
    }

    @Override
    public boolean handleMessage(Message msg) {
        Runnable r = msg.getCallback();
        if (r != null) {
            r.run();
            return true;
        } else {
            return false;
        }
    }

    public String getTunReopenStatus() {
        String currentConfiguration = getTunConfigString();
        if (currentConfiguration.equals(mLastTunConfig)) {
            return "NOACTION";
        } else {
            String release = Build.VERSION.RELEASE;
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT && !release.startsWith("4.4.3")
                    && !release.startsWith("4.4.4") && !release.startsWith("4.4.5") && !release.startsWith("4.4.6"))
                // There will be probably no 4.4.4 or 4.4.5 version, so don't waste effort to do parsing here
                return "OPEN_AFTER_CLOSE";
            else
                return "OPEN_BEFORE_CLOSE";
        }
    }
}
