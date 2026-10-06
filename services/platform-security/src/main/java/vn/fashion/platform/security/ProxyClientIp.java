package vn.fashion.platform.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/** The socket peer, never an incoming header, decides whether a proxy is trusted. */
public final class ProxyClientIp {
  public static final String HEADER = "X-Client-IP";
  private final List<String> proxies;

  public ProxyClientIp(String proxies) {
    this.proxies = Arrays.stream(proxies.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  public String resolve(String socketPeer, String forwarded) {
    InetAddress peer = literal(socketPeer);
    if (peer == null) {
      throw new IllegalArgumentException("A literal socket address is required");
    }
    InetAddress client = literal(forwarded);
    if (client != null) {
      for (String proxy : proxies) {
        try {
          if (Arrays.asList(InetAddress.getAllByName(proxy)).contains(peer)) {
            return client.getHostAddress();
          }
        } catch (UnknownHostException ignored) {
          // A missing configured proxy is never trusted; use the socket peer instead.
        }
      }
    }
    return peer.getHostAddress();
  }

  private static InetAddress literal(String value) {
    if (value == null || value.contains("%") ||
        !(value.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+") ||
          (value.contains(":") && value.matches("[0-9a-fA-F:.]+")))) {
      return null;
    }
    try {
      return InetAddress.getByName(value);
    } catch (UnknownHostException ignored) {
      return null;
    }
  }
}
