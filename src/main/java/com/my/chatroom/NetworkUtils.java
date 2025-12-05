package com.my.chatroom;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class NetworkUtils {

    /**
     * 获取本机的局域网 IP 地址 (IPv4)
     * 优先返回 192.168.x.x 等局域网地址，排除 127.0.0.1 和虚拟网卡
     */
    public static String getLocalHostLANAddress() {
        try {
            Enumeration<NetworkInterface> allNetInterfaces = NetworkInterface.getNetworkInterfaces();
            InetAddress candidateAddress = null;

            while (allNetInterfaces.hasMoreElements()) {
                NetworkInterface netInterface = allNetInterfaces.nextElement();

                // 排除回环接口(127.0.0.1)、未启动的接口、虚拟接口(通常名字包含vmnet/docker等，这里简单排除回环)
                if (netInterface.isLoopback() || !netInterface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = netInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress ip = addresses.nextElement();
                    if (ip != null && ip instanceof Inet4Address) {
                        // 如果是局域网地址 (192.*, 10.*, 172.*)，直接返回
                        if (ip.isSiteLocalAddress()) {
                            return ip.getHostAddress();
                        }
                        // 如果没找到局域网地址，先存一个非回环地址做备选
                        if (candidateAddress == null) {
                            candidateAddress = ip;
                        }
                    }
                }
            }
            // 如果没找到最佳的 site-local 地址，就返回备选的
            if (candidateAddress != null) {
                return candidateAddress.getHostAddress();
            }
            // 实在不行，返回 localhost
            InetAddress jdkSuppliedAddress = InetAddress.getLocalHost();
            return jdkSuppliedAddress.getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}