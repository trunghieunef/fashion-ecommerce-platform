package vn.fashion.platform.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** The socket peer, never an incoming header, decides whether a proxy is trusted. */
public final class ProxyClientIp {
  public static final String HEADER = "X-Client-IP";
  private static final String OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
  private static final Pattern IPV4 = Pattern.compile("(?:" + OCTET + "\\.){3}" + OCTET);
  private final List<String> proxies;
  private final boolean requiresDns;

  public ProxyClientIp(String proxies) {
    this.proxies = Arrays.stream(proxies.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    requiresDns = this.proxies.stream().anyMatch(proxy -> literal(proxy) == null);
  }

  public boolean requiresDns() {
    return requiresDns;
  }

  public String resolve(String socketPeer, String forwarded) {
    // A zone belongs to the actual socket interface, never to a client-supplied header.
    if (socketPeer != null && socketPeer.contains(":") && socketPeer.contains("%")) {
      socketPeer = socketPeer.substring(0, socketPeer.indexOf('%'));
    }
    InetAddress peer = literal(socketPeer);
    if (peer == null) {
      throw new IllegalArgumentException("A literal socket address is required");
    }
    if (forwarded != null) {
      for (String proxy : proxies) {
        try {
          if (Arrays.asList(InetAddress.getAllByName(proxy)).contains(peer)) {
            InetAddress client = literal(forwarded);
            return client == null ? peer.getHostAddress() : client.getHostAddress();
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
        !(IPV4.matcher(value).matches() ||
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
