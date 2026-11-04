package com.divatouch.app;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serves the game files from the APK to the WebView on 127.0.0.1 only. Supports byte ranges so videos can seek. */
final class AssetServer extends Thread {
    private final AssetManager assets;
    private final int port;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    AssetServer(AssetManager a, int port) { this.assets = a; this.port = port; setDaemon(true); setName("asset-server"); }

    @Override public void run() {
        try (ServerSocket ss = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))) {
            ss.setReuseAddress(true);
            while (true) { Socket c = ss.accept(); pool.execute(() -> handle(c)); }
        } catch (IOException ignored) { }
    }

    private static String type(String p) {
        String l = p.toLowerCase();
        if (l.endsWith(".html")) return "text/html; charset=utf-8";
        if (l.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (l.endsWith(".css")) return "text/css; charset=utf-8";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".mp4")) return "video/mp4";
        if (l.endsWith(".mp3")) return "audio/mpeg";
        if (l.endsWith(".ogg")) return "audio/ogg";
        if (l.endsWith(".webp")) return "image/webp";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private void handle(Socket c) {
        try (Socket s = c) {
            s.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = in.readLine(); if (line == null) return;
            String[] parts = line.split(" "); if (parts.length < 2) return;
            boolean head = parts[0].equals("HEAD");
            String range = null, h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                int i = h.indexOf(':');
                if (i > 0 && h.substring(0, i).trim().equalsIgnoreCase("range")) range = h.substring(i + 1).trim();
            }
            String path = URLDecoder.decode(parts[1].split("\\?")[0], "UTF-8");
            if (path.equals("/") || path.isEmpty()) path = "/index.html";
            if (path.contains("..")) { send(s, 403, "Forbidden"); return; }
            String asset = path.substring(1);
            OutputStream out = s.getOutputStream();
            // media is stored uncompressed, so it can be opened as a seekable file region
            AssetFileDescriptor fd = null; long len;
            try { fd = assets.openFd(asset); len = fd.getLength(); } catch (IOException e) { fd = null; len = -1; }
            if (fd == null) {   // compressed text files (html etc.): read fully
                byte[] data;
                try (InputStream is = assets.open(asset)) { data = is.readAllBytes(); } catch (IOException e) { send(s, 404, "Not found"); return; }
                String hd = "HTTP/1.1 200 OK\r\nContent-Type: " + type(asset) + "\r\nContent-Length: " + data.length
                        + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
                out.write(hd.getBytes(StandardCharsets.ISO_8859_1)); if (!head) out.write(data); out.flush(); return;
            }
            long a = 0, b = len - 1; boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String[] r = range.substring(6).split(",")[0].split("-", -1);
                try {
                    if (r[0].isEmpty()) { long n = Long.parseLong(r[1]); a = Math.max(0, len - n); }
                    else { a = Long.parseLong(r[0]); if (r.length > 1 && !r[1].isEmpty()) b = Math.min(len - 1, Long.parseLong(r[1])); }
                    partial = true;
                } catch (NumberFormatException ignored) { }
                if (a > b || a >= len) {
                    out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + len + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    fd.close(); return;
                }
            }
            long n = b - a + 1;
            String hd = (partial ? "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes " + a + "-" + b + "/" + len + "\r\n" : "HTTP/1.1 200 OK\r\n")
                    + "Content-Type: " + type(asset) + "\r\nContent-Length: " + n + "\r\nAccept-Ranges: bytes\r\nCache-Control: max-age=3600\r\nConnection: close\r\n\r\n";
            out.write(hd.getBytes(StandardCharsets.ISO_8859_1));
            if (!head) {
                try (FileInputStream fis = new FileInputStream(fd.getFileDescriptor())) {
                    fis.getChannel().position(fd.getStartOffset() + a);
                    byte[] buf = new byte[256 * 1024]; long left = n;
                    while (left > 0) { int r = fis.read(buf, 0, (int) Math.min(buf.length, left)); if (r < 0) break; out.write(buf, 0, r); left -= r; }
                }
            }
            out.flush(); fd.close();
        } catch (IOException ignored) { }
    }

    private static void send(Socket s, int code, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        s.getOutputStream().write(("HTTP/1.1 " + code + " " + msg + "\r\nContent-Type: text/plain\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        s.getOutputStream().write(b);
    }
}
