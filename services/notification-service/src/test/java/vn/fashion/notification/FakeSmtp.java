package vn.fashion.notification;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Scripted SMTP server on a ServerSocket; stores the DATA of every message it accepts. */
final class FakeSmtp implements AutoCloseable {
  enum Mode { OK, TEMP_AT_MAIL, PERM_AT_RCPT, SILENT_AFTER_DATA, GREETING_421, CLOSE_AFTER_EHLO }

  volatile Mode mode = Mode.OK;
  final List<String> messages = new CopyOnWriteArrayList<>();
  private final ServerSocket server;

  FakeSmtp() throws IOException {
    server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread accept = new Thread(() -> {
      while (!server.isClosed()) {
        try {
          Socket socket = server.accept();
          Thread t = new Thread(() -> serve(socket), "fake-smtp-conn");
          t.setDaemon(true);
          t.start();
        } catch (IOException e) { return; }
      }
    }, "fake-smtp");
    accept.setDaemon(true);
    accept.start();
  }

  int port() { return server.getLocalPort(); }

  /** A port nothing listens on. */
  static int closedPort() throws IOException {
    try (var s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return s.getLocalPort(); }
  }

  private void serve(Socket socket) {
    try (socket;
         var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
         Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
      if (mode == Mode.GREETING_421) { reply(out, "421 service not available"); return; }
      reply(out, "220 fake ESMTP");
      String line;
      while ((line = in.readLine()) != null) {
        String cmd = line.toUpperCase();
        if (mode == Mode.CLOSE_AFTER_EHLO && (cmd.startsWith("EHLO") || cmd.startsWith("HELO"))) return;
        if (cmd.startsWith("MAIL")) reply(out, mode == Mode.TEMP_AT_MAIL ? "451 try again later" : "250 ok");
        else if (cmd.startsWith("RCPT")) reply(out, mode == Mode.PERM_AT_RCPT ? "550 no such mailbox" : "250 ok");
        else if (cmd.equals("DATA")) {
          reply(out, "354 go ahead");
          StringBuilder data = new StringBuilder();
          while ((line = in.readLine()) != null && !line.equals(".")) data.append(line).append('\n');
          if (mode == Mode.SILENT_AFTER_DATA) {
            // Hold the connection without answering until the client gives up.
            while (in.readLine() != null) { }
            return;
          }
          messages.add(data.toString());
          reply(out, "250 queued");
        } else if (cmd.startsWith("QUIT")) { reply(out, "221 bye"); return; }
        else reply(out, "250 ok");
      }
    } catch (IOException ignored) { }
  }

  private static void reply(Writer out, String line) throws IOException { out.write(line + "\r\n"); out.flush(); }

  @Override public void close() throws IOException { server.close(); }
}
