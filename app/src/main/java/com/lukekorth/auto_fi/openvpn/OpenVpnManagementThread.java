package com.lukekorth.auto_fi.openvpn;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;

import com.lukekorth.auto_fi.BuildConfig;
import com.lukekorth.auto_fi.R;
import com.lukekorth.auto_fi.utilities.Logger;

import junit.framework.Assert;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Vector;

public class OpenVpnManagementThread implements Runnable, OpenVPNManagement {

    private LocalSocket mSocket;
    private OpenVpn mOpenVpn;
    private LinkedList<FileDescriptor> mFDList = new LinkedList<>();
    private LocalServerSocket mServerSocket;
    private boolean mWaitingForRelease = false;
    private long mLastHoldRelease = 0;

    private static final Vector<OpenVpnManagementThread> active = new Vector<>();

    private PauseReason lastPauseReason = PauseReason.NO_NETWORK;
    private PausedStateCallback mPauseCallback;
    private boolean mShuttingDown;

    public OpenVpnManagementThread(OpenVpn openVpn) {
        mOpenVpn = openVpn;
    }

    public boolean openManagementInterface(Context context) {
        String socketName = (context.getCacheDir().getAbsolutePath() + "/" + "mgmtsocket");

        int tries = 8;
        LocalSocket serverSocketLocal = new LocalSocket();
        while (tries > 0 && !serverSocketLocal.isBound()) {
            try {
                serverSocketLocal.bind(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (IOException e) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {}
            }

            tries--;
        }

        try {
            mServerSocket = new LocalServerSocket(serverSocketLocal.getFileDescriptor());
            return true;
        } catch (IOException e) {
            Logger.error(e);
        }

        return false;
    }

    public void managementCommand(String cmd) {
        try {
            if (mSocket != null && mSocket.getOutputStream() != null) {
                mSocket.getOutputStream().write(cmd.getBytes());
                mSocket.getOutputStream().flush();
            }
        } catch (IOException ignored) {}
    }

    @Override
    public void run() {
        byte[] buffer = new byte[2048];

        String pendingInput = "";
        synchronized (active) {
            active.add(this);
        }

        try {
            mSocket = mServerSocket.accept();
            InputStream instream = mSocket.getInputStream();
            mServerSocket.close();

            while (true) {
                int numbytesread = instream.read(buffer);
                if (numbytesread == -1) {
                    return;
                }

                FileDescriptor[] fds = null;
                try {
                    fds = mSocket.getAncillaryFileDescriptors();
                } catch (IOException e) {
                    Logger.error("Error reading fds from socket." + e.getMessage());
                }

                if (fds != null) {
                    Collections.addAll(mFDList, fds);
                }

                String input = new String(buffer, 0, numbytesread, "UTF-8");

                pendingInput += input;
                pendingInput = processInput(pendingInput);
            }
        } catch (IOException e) {
            if (!e.getMessage().equals("socket closed") && !e.getMessage().equals("Connection reset by peer")) {
                Logger.error(e);
            }
        }

        synchronized (active) {
            active.remove(this);
        }
    }

    private void protectFileDescriptor(FileDescriptor fd) {
        try {
            Method getInt = FileDescriptor.class.getDeclaredMethod("getInt$");
            int fdint = (Integer) getInt.invoke(fd);

            boolean result = mOpenVpn.getVpnService().protect(fdint);
            if (!result) {
                Logger.warn("Could not protect VPN socket");
            }

            NativeUtils.jniclose(fdint);
        } catch (NoSuchMethodException | IllegalArgumentException | InvocationTargetException | IllegalAccessException |
                NullPointerException e) {
            Logger.error("Failed to retrieve fd from socket (" + fd + ")." + e.getMessage());
        }
    }

    private String processInput(String pendingInput) {
        while (pendingInput.contains("\n")) {
            String[] tokens = pendingInput.split("\\r?\\n", 2);
            processCommand(tokens[0]);
            if (tokens.length == 1) {
                // No second part, newline was at the end
                pendingInput = "";
            } else {
                pendingInput = tokens[1];
            }
        }

        return pendingInput;
    }

    private void processCommand(String command) {
        if (command.startsWith(">") && command.contains(":")) {
            String[] parts = command.split(":", 2);
            String cmd = parts[0].substring(1);
            String argument = parts[1];

            switch (cmd) {
                case "INFO": case "BYTECOUNT":
                    // ignore
                    return;
                case "HOLD":
                    handleHold();
                    break;
                case "NEED-OK":
                    processNeedCommand(argument);
                    break;
                case "STATE":
                    if (!mShuttingDown)
                        processState(argument);
                    break;
                case "PROXY":
                    processProxyCMD(argument);
                    break;
                case "LOG":
                    processLogMessage(argument);
                    break;
                default:
                    Logger.warn("MGMT: Got unrecognized command" + command);
                    break;
            }
        } else if (command.startsWith("SUCCESS:")) {
            // ignore
        } else if (command.startsWith("PROTECTFD: ")) {
            FileDescriptor fdtoprotect = mFDList.pollFirst();
            if (fdtoprotect != null) {
                protectFileDescriptor(fdtoprotect);
            }
        } else {
            Logger.warn("MGMT: Got unrecognized line from management: " + command);
        }
    }

    private void processLogMessage(String argument) {
        String[] args = argument.split(",", 4);
        switch (args[1]) {
            case "W":
                Logger.warn(args[3]);
                break;
            case "D":
                Logger.debug(args[3]);
                break;
            case "F":
                Logger.error(args[3]);
                break;
            default:
                Logger.info(args[3]);
                break;
        }
    }

    boolean shouldBeRunning() {
        return mPauseCallback != null && mPauseCallback.shouldBeRunning();
    }

    private void handleHold() {
        if (shouldBeRunning()) {
            releaseHoldCmd();
        } else {
            mWaitingForRelease = true;
            mOpenVpn.updateNotification(R.string.state_nonetwork);
        }
    }

    private void releaseHoldCmd() {
        if ((System.currentTimeMillis() - mLastHoldRelease) < 5000) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {}
        }

        mWaitingForRelease = false;
        mLastHoldRelease = System.currentTimeMillis();
        managementCommand("hold release\n");
        managementCommand("bytecount " + mBytecountInterval + "\n");
        managementCommand("state on\n");
    }

    public void releaseHold() {
        if (mWaitingForRelease) {
            releaseHoldCmd();
        }
    }

    private void processProxyCMD(String argument) {
        String[] args = argument.split(",", 3);
        SocketAddress proxyaddr = ProxyDetection.detectProxy();

        if (args.length >= 2) {
            String proto = args[1];
            if (proto.equals("UDP")) {
                proxyaddr = null;
            }
        }

        if (proxyaddr instanceof InetSocketAddress) {
            InetSocketAddress isa = (InetSocketAddress) proxyaddr;
            String proxycmd = String.format(Locale.ENGLISH, "proxy HTTP %s %d\n", isa.getHostName(), isa.getPort());
            managementCommand(proxycmd);

            Logger.info("Using proxy " + isa.getHostName() + " " + isa.getPort());
        } else {
            managementCommand("proxy NONE\n");
        }
    }

    private void processState(String argument) {
        String[] args = argument.split(",", 3);
        String currentstate = args[1];

        if (args[2].equals(",,")) {
            mOpenVpn.updateNotification(getLocalizedState(currentstate));
        } else {
            mOpenVpn.updateNotification(getLocalizedState(currentstate));
        }
    }

    private void processNeedCommand(String argument) {
        int p1 = argument.indexOf('\'');
        int p2 = argument.indexOf('\'', p1 + 1);

        String needed = argument.substring(p1 + 1, p2);
        String extra = argument.split(":", 2)[1];

        String status = "ok";

        switch (needed) {
            case "PROTECTFD":
                FileDescriptor fdtoprotect = mFDList.pollFirst();
                protectFileDescriptor(fdtoprotect);
                break;
            case "DNSSERVER":
                mOpenVpn.addDNS(extra);
                break;
            case "DNSDOMAIN":
                mOpenVpn.setDomain(extra);
                break;
            case "ROUTE":
                String[] routeparts = extra.split(" ");

                if (routeparts.length == 5) {
                    if (BuildConfig.DEBUG) Assert.assertEquals("dev", routeparts[3]);
                    mOpenVpn.addRoute(routeparts[0], routeparts[1], routeparts[2], routeparts[4]);
                } else if (routeparts.length >= 3) {
                    mOpenVpn.addRoute(routeparts[0], routeparts[1], routeparts[2], null);
                } else {
                    Logger.error("Unrecognized ROUTE cmd:" + Arrays.toString(routeparts) + " | " + argument);
                }

                break;
            case "ROUTE6":
                String[] routeparts6 = extra.split(" ");
                mOpenVpn.addRoutev6(routeparts6[0], routeparts6[1]);
                break;
            case "IFCONFIG":
                String[] ifconfigparts = extra.split(" ");
                int mtu = Integer.parseInt(ifconfigparts[2]);
                mOpenVpn.setLocalIP(ifconfigparts[0], ifconfigparts[1], mtu, ifconfigparts[3]);
                break;
            case "IFCONFIG6":
                mOpenVpn.setLocalIPv6(extra);
                break;
            case "PERSIST_TUN_ACTION":
                status = mOpenVpn.getTunReopenStatus();
                break;
            case "OPENTUN":
                if (sendTunFD(needed, extra)) {
                    return;
                } else {
                    status = "cancel";
                }
                break;
            default:
                Logger.error("Unknown needok command " + argument);
                return;
        }

        String cmd = String.format("needok '%s' %s\n", needed, status);
        managementCommand(cmd);
    }

    private boolean sendTunFD(String needed, String extra) {
        if (!extra.equals("tun")) {
            Logger.error("Device type " + extra + " requested, but only tun is accepted");
            return false;
        }

        ParcelFileDescriptor pfd = mOpenVpn.openTun();
        if (pfd == null) {
            return false;
        }

        Method setInt;
        int fdint = pfd.getFd();
        try {
            setInt = FileDescriptor.class.getDeclaredMethod("setInt$", int.class);
            FileDescriptor fdtosend = new FileDescriptor();

            setInt.invoke(fdtosend, fdint);

            FileDescriptor[] fds = {fdtosend};
            mSocket.setFileDescriptorsForSend(fds);

            // Trigger a send so we can close the fd on our side of the channel
            // The API documentation fails to mention that it will not reset the file descriptor to
            // be send and will happily send the file descriptor on every write ...
            String cmd = String.format("needok '%s' %s\n", needed, "ok");
            managementCommand(cmd);

            // Set the FileDescriptor to null to stop this mad behavior
            mSocket.setFileDescriptorsForSend(null);

            pfd.close();

            return true;
        } catch (NoSuchMethodException | IllegalArgumentException | InvocationTargetException |
                IOException | IllegalAccessException exp) {
            Logger.error("Could not send fd over socket" + exp.getMessage());
        }

        return false;
    }

    private static boolean stopOpenVPN() {
        synchronized (active) {
            boolean sendCMD = false;
            for (OpenVpnManagementThread mt : active) {
                mt.managementCommand("signal SIGINT\n");
                sendCMD = true;
                try {
                    if (mt.mSocket != null) {
                        mt.mSocket.close();
                    }
                } catch (IOException ignored) {}
            }

            return sendCMD;
        }
    }

    @Override
    public void networkChange(boolean samenetwork) {
        if (mWaitingForRelease) {
            releaseHold();
        } else if (samenetwork) {
            managementCommand("network-change samenetwork\n");
        } else {
            managementCommand("network-change\n");
        }
    }

    @Override
    public void setPauseCallback(PausedStateCallback callback) {
        mPauseCallback = callback;
    }

    public void signalusr1() {
        if (!mWaitingForRelease) {
            managementCommand("signal SIGUSR1\n");
        } else {
            mOpenVpn.updateNotification(R.string.state_nonetwork);
        }
    }

    public void reconnect() {
        signalusr1();
        releaseHold();
    }

    @Override
    public void pause(PauseReason reason) {
        lastPauseReason = reason;
        signalusr1();
    }

    @Override
    public void resume() {
        releaseHold();
        lastPauseReason = PauseReason.NO_NETWORK;
    }

    @Override
    public boolean stopVPN(boolean replaceConnection) {
        mShuttingDown = true;
        return stopOpenVPN();
    }

    private int getLocalizedState(String state) {
        switch (state) {
            case "CONNECTING":
                return R.string.state_connecting;
            case "WAIT":
                return R.string.state_wait;
            case "AUTH":
                return R.string.state_auth;
            case "GET_CONFIG":
                return R.string.state_get_config;
            case "ASSIGN_IP":
                return R.string.state_assign_ip;
            case "ADD_ROUTES":
                return R.string.state_add_routes;
            case "CONNECTED":
                return R.string.state_connected;
            case "DISCONNECTED":
                return R.string.state_disconnected;
            case "RECONNECTING":
                return R.string.state_reconnecting;
            case "EXITING":
                return R.string.state_exiting;
            case "RESOLVE":
                return R.string.state_resolve;
            case "TCP_CONNECT":
                return R.string.state_tcp_connect;
            default:
                return R.string.unknown_state;
        }
    }
}