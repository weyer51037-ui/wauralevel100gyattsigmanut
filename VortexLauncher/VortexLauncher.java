import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.stream.*;
import java.util.concurrent.*;

/** Vortex Launcher: installs Minecraft 1.21.1 + Fabric + Fabric API + Vortex Client, then launches it (offline name). */
public class VortexLauncher {
    static final Path HOME = Paths.get(System.getProperty("user.home"), ".vortex-launcher");
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
    static Color ACC = new Color(0x7C5CFF), ACC2 = new Color(0x38D6FF);
    static final Color BG = new Color(0x0E0B1A), PANEL = new Color(0x171329), TXT = new Color(0xECE9FF), DIM = new Color(0x8D87AD);

    // ---------- minimal JSON ----------
    static class J {
        final String s; int i;
        J(String s) { this.s = s; }
        static Object parse(String s) { return new J(s).v(); }
        void ws() { while (i < s.length() && s.charAt(i) <= ' ') i++; }
        Object v() {
            ws(); char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); ws(); if (s.charAt(i) == '}') { i++; return m; }
                while (true) { ws(); String k = str(); ws(); i++; m.put(k, v()); ws(); if (s.charAt(i++) == '}') return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws(); if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(v()); ws(); if (s.charAt(i++) == ']') return l; } }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }
        String str() {
            i++; StringBuilder b = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') { char e = s.charAt(i++);
                    switch (e) { case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break; default: b.append(e); }
                } else b.append(c);
            }
            i++; return b.toString();
        }
    }
    @SuppressWarnings("unchecked") static Map<String, Object> M(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") static List<Object> L(Object o) { return (List<Object>) o; }

    // ---------- network helpers ----------
    static String get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "VortexLauncher/0.1").build(), HttpResponse.BodyHandlers.ofString()).body();
    }
    static void download(String url, Path dest) throws Exception {
        if (Files.exists(dest) && Files.size(dest) > 0) return;
        Files.createDirectories(dest.getParent());
        Path tmp = Paths.get(dest + ".part");
        HttpResponse<Path> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "VortexLauncher/0.1").build(), HttpResponse.BodyHandlers.ofFile(tmp));
        if (r.statusCode() != 200) { Files.deleteIfExists(tmp); throw new IOException("HTTP " + r.statusCode() + " for " + url); }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    // ---------- rules ----------
    static String osName() { String o = System.getProperty("os.name").toLowerCase(); return o.contains("win") ? "windows" : o.contains("mac") ? "osx" : "linux"; }
    static boolean allowed(Object rules) {
        if (rules == null) return true;
        boolean res = false;
        for (Object ro : L(rules)) {
            Map<String, Object> r = M(ro); boolean match = true;
            if (r.containsKey("features")) match = false;
            if (r.containsKey("os")) {
                Map<String, Object> os = M(r.get("os"));
                if (os.containsKey("name") && !os.get("name").equals(osName())) match = false;
                if (os.containsKey("arch")) { String a = System.getProperty("os.arch"); boolean x86 = os.get("arch").equals("x86"); if (x86 != (a.equals("x86") || a.equals("i386"))) match = false; }
            }
            if (match) res = r.get("action").equals("allow");
        }
        return res;
    }
    static String mavenPath(String name) {
        String[] p = name.split(":"); String ext = "jar";
        String file = p[1] + "-" + p[2] + (p.length > 3 ? "-" + p[3] : "") + "." + ext;
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + file;
    }

    // ---------- install + launch ----------
    static String MC = "1.21.1", LOADER = "", profile = "Balanced", loaded = "";
    static boolean useVortex = true;
    static final Map<String, String> VURL = new LinkedHashMap<>();
    static Map<String, Object> vanilla, fabric;
    static final List<Path> classpath = new ArrayList<>();
    static java.util.function.Consumer<String> LOG = s -> {};
    static java.util.function.BiConsumer<Double, String> PROG = (a, b) -> {};
    static final String[] OPT = {"sodium", "immediatelyfast", "entityculling", "moreculling", "enhanced-block-entities", "lithium", "ferrite-core",
        "modernfix", "krypton", "noisium", "dynamic-fps", "exordium", "fastquit", "debugify", "reeses-sodium-options", "sodium-extra"};

    static Path inst() { return HOME.resolve("instances").resolve(MC); }
    static Path appDir() {
        try { return Paths.get(VortexLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent(); } catch (Exception e) { return Paths.get("."); }
    }
    static String sha1(Path p) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(p)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        StringBuilder sb = new StringBuilder(); for (byte x : md.digest()) sb.append(String.format("%02x", x)); return sb.toString();
    }
    /** Download with retry and optional SHA-1 verification; existing files are kept. */
    static void fetch(String url, Path dest, String sha) throws Exception {
        if (Files.exists(dest) && Files.size(dest) > 0) return;
        Files.createDirectories(dest.getParent()); Path tmp = Paths.get(dest + ".part");
        for (int attempt = 1; ; attempt++) {
            try {
                Files.deleteIfExists(tmp);
                HttpResponse<Path> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "VortexLauncher/0.2").build(), HttpResponse.BodyHandlers.ofFile(tmp));
                if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
                if (sha != null && !sha.equalsIgnoreCase(sha1(tmp))) throw new IOException("Checksum mismatch for " + url);
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING); return;
            } catch (Exception e) { Files.deleteIfExists(tmp); if (attempt >= 3) throw e; }
        }
    }
    static void extract(Path jar, Path dir) throws IOException {
        Files.createDirectories(dir);
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(Files.newInputStream(jar))) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory() || e.getName().startsWith("META-INF")) continue;
                Path out = dir.resolve(e.getName()).normalize(); if (!out.startsWith(dir)) continue;
                Files.createDirectories(out.getParent()); Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
    static void loadVersions() throws Exception {
        Map<String, Object> man = M(J.parse(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")));
        VURL.clear();
        for (Object o : L(man.get("versions"))) { Map<String, Object> v = M(o); if ("release".equals(v.get("type"))) VURL.put((String) v.get("id"), (String) v.get("url")); }
    }

    static void install(java.util.function.Consumer<String> log) throws Exception {
        PROG.accept(0.02, "Preparing " + MC + "...");
        if (VURL.isEmpty()) loadVersions();
        String vurl = VURL.get(MC); if (vurl == null) throw new IOException("Minecraft " + MC + " not found");
        Path vjson = HOME.resolve("versions/" + MC + "/" + MC + ".json"); fetch(vurl, vjson, null);
        vanilla = M(J.parse(Files.readString(vjson)));
        Object jv = vanilla.get("javaVersion");
        if (jv != null) { int req = ((Number) M(jv).get("majorVersion")).intValue(), cur = Runtime.version().feature();
            if (req > cur) throw new IOException("Minecraft " + MC + " needs Java " + req + " but the launcher is running on Java " + cur);
            if (req < 17) log.accept("Warning: " + MC + " was made for Java " + req + " and may not start on Java " + cur + "."); }

        fabric = null; LOADER = "";
        try {
            Object lv = J.parse(get("https://meta.fabricmc.net/v2/versions/loader/" + MC));
            if (lv instanceof List) for (Object o : L(lv)) { Map<String, Object> ld = M(M(o).get("loader")); if (Boolean.TRUE.equals(ld.get("stable"))) { LOADER = (String) ld.get("version"); break; } }
        } catch (Exception e) { LOADER = ""; }
        if (!LOADER.isEmpty()) {
            Path fj = HOME.resolve("versions/fabric-" + MC + "-" + LOADER + ".json");
            fetch("https://meta.fabricmc.net/v2/versions/loader/" + MC + "/" + LOADER + "/profile/json", fj, null);
            fabric = M(J.parse(Files.readString(fj))); log.accept("Fabric Loader " + LOADER);
        } else log.accept("No Fabric Loader for " + MC + ": launching vanilla without mods.");

        classpath.clear(); Path lib = HOME.resolve("libraries"), nat = inst().resolve("natives"); int n = 0;
        for (Object o : L(vanilla.get("libraries"))) {
            Map<String, Object> l = M(o); if (!allowed(l.get("rules"))) continue;
            Map<String, Object> dl = M(l.get("downloads")); if (dl == null) continue;
            Map<String, Object> art = M(dl.get("artifact"));
            if (art != null) { Path p = lib.resolve((String) art.get("path")); fetch((String) art.get("url"), p, (String) art.get("sha1")); classpath.add(p); }
            if (l.containsKey("natives") && dl.get("classifiers") != null) {
                String key = (String) M(l.get("natives")).get(osName());
                if (key != null) { key = key.replace("${arch}", System.getProperty("os.arch").contains("64") ? "64" : "32");
                    Map<String, Object> c = M(M(dl.get("classifiers")).get(key));
                    if (c != null) { Path p = lib.resolve((String) c.get("path")); fetch((String) c.get("url"), p, (String) c.get("sha1")); extract(p, nat); } }
            }
            PROG.accept(0.05 + 0.15 * (++n) / L(vanilla.get("libraries")).size(), "Libraries");
        }
        if (fabric != null) for (Object o : L(fabric.get("libraries"))) {
            Map<String, Object> l = M(o); String path = mavenPath((String) l.get("name"));
            String base = l.containsKey("url") ? (String) l.get("url") : "https://maven.fabricmc.net/"; if (!base.endsWith("/")) base += "/";
            Path p = lib.resolve(path); fetch(base + path, p, null); classpath.add(p);
        }
        Map<String, Object> cl = M(M(vanilla.get("downloads")).get("client"));
        Path client = HOME.resolve("versions/" + MC + "/" + MC + ".jar"); log.accept("Minecraft client jar...");
        fetch((String) cl.get("url"), client, (String) cl.get("sha1")); classpath.add(client);

        PROG.accept(0.2, "Downloading assets..."); Map<String, Object> ai = M(vanilla.get("assetIndex"));
        Path idx = HOME.resolve("assets/indexes/" + ai.get("id") + ".json"); fetch((String) ai.get("url"), idx, (String) ai.get("sha1"));
        Map<String, Object> idxJ = M(J.parse(Files.readString(idx))), objs = M(idxJ.get("objects"));
        Set<String> hashes = new LinkedHashSet<>(); for (Object o : objs.values()) hashes.add((String) M(o).get("hash"));
        int total = hashes.size(); ExecutorService ex = Executors.newFixedThreadPool(16);
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(); List<Future<?>> fs = new ArrayList<>();
        for (String h : hashes) { String pre = h.substring(0, 2);
            fs.add(ex.submit(() -> { fetch("https://resources.download.minecraft.net/" + pre + "/" + h, HOME.resolve("assets/objects/" + pre + "/" + h), h);
                int d = done.incrementAndGet(); if (d % 50 == 0 || d == total) PROG.accept(0.2 + 0.6 * d / total, "Assets " + d + "/" + total); return null; })); }
        try { for (Future<?> fu : fs) fu.get(); } finally { ex.shutdown(); }
        if (Boolean.TRUE.equals(idxJ.get("virtual"))) for (Map.Entry<String, Object> e : objs.entrySet()) {
            String h = (String) M(e.getValue()).get("hash"); Path dst = HOME.resolve("assets/virtual/" + ai.get("id") + "/" + e.getKey());
            if (!Files.exists(dst)) { Files.createDirectories(dst.getParent()); Files.copy(HOME.resolve("assets/objects/" + h.substring(0, 2) + "/" + h), dst); } }

        Path mods = inst().resolve("mods"); Files.createDirectories(mods);
        if (fabric != null) installMods(mods, log);
        loaded = MC; log.accept("Install complete."); PROG.accept(1.0, "Ready to play");
    }

    static void installMods(Path mods, java.util.function.Consumer<String> log) throws Exception {
        Path managed = inst().resolve("managed-mods.txt");
        if (Files.exists(managed)) for (String f : Files.readAllLines(managed)) { Files.deleteIfExists(mods.resolve(f)); Files.deleteIfExists(inst().resolve("mods-disabled").resolve(f)); }
        try (Stream<Path> s = Files.list(mods)) { for (Path p : (Iterable<Path>) s.filter(x -> x.getFileName().toString().startsWith("vortex-client"))::iterator) Files.deleteIfExists(p); }
        List<String> slugs = new ArrayList<>(); slugs.add("fabric-api");
        boolean vortex = useVortex && MC.equals("1.21.1");
        for (String o : OPT) { if (vortex && o.equals("entityculling")) { log.accept("Skipping entityculling (Vortex has its own entity culling)."); continue; } slugs.add(o); }
        List<String> got = new ArrayList<>(); Set<String> seen = new HashSet<>(); int i = 0;
        for (String sl : slugs) {
            PROG.accept(0.8 + 0.18 * (i++) / slugs.size(), "Mods " + i + "/" + slugs.size());
            try { installMod(sl, mods, seen, got, log); } catch (Exception e) { log.accept("Failed " + sl + ": " + e.getMessage()); }
        }
        Files.write(managed, got);
        if (vortex) installVortexJar(mods, log);
    }
    static void installMod(String id, Path mods, Set<String> seen, List<String> got, java.util.function.Consumer<String> log) throws Exception {
        if (!seen.add(id)) return;
        Object parsed; try { parsed = J.parse(get("https://api.modrinth.com/v2/project/" + id + "/version?game_versions=%5B%22" + MC + "%22%5D&loaders=%5B%22fabric%22%5D")); } catch (Exception e) { parsed = null; }
        if (!(parsed instanceof List) || L(parsed).isEmpty()) { log.accept("No compatible release for " + id + " on " + MC + " (skipped)"); return; }
        Map<String, Object> pick = null;
        for (Object o : L(parsed)) if ("release".equals(M(o).get("version_type"))) { pick = M(o); break; }
        if (pick == null) pick = M(L(parsed).get(0));
        for (Object d : L(pick.get("dependencies"))) { Map<String, Object> dm = M(d);
            if ("required".equals(dm.get("dependency_type")) && dm.get("project_id") != null) installMod((String) dm.get("project_id"), mods, seen, got, log); }
        Map<String, Object> file = null;
        for (Object o : L(pick.get("files"))) if (Boolean.TRUE.equals(M(o).get("primary"))) file = M(o);
        if (file == null) file = M(L(pick.get("files")).get(0));
        String fn = (String) file.get("filename");
        fetch((String) file.get("url"), mods.resolve(fn), (String) M(file.get("hashes")).get("sha1")); got.add(fn); log.accept("Mod: " + fn);
        String[] mm = readMeta(mods.resolve(fn)); if (disabledSet().contains(mm[0])) { moveMod(mods.resolve(fn), true, mm[0]); log.accept("(kept disabled) " + fn); }
    }
    static void installVortexJar(Path mods, java.util.function.Consumer<String> log) throws Exception {
        for (Path d : List.of(appDir(), Paths.get("."), HOME)) {
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.list(d)) {
                Optional<Path> j = s.filter(p -> p.getFileName().toString().matches("vortex-client.*\\.jar")).findFirst();
                if (j.isPresent()) { Files.copy(j.get(), mods.resolve(j.get().getFileName()), StandardCopyOption.REPLACE_EXISTING); log.accept("Vortex mod: " + j.get().getFileName()); return; }
            }
        }
        log.accept("WARNING: no vortex-client*.jar found next to the launcher; put it in " + HOME);
    }

    static void applyProfile(Path game) throws IOException {
        try { if (Integer.parseInt(MC.split("\\.")[1]) < 18) return; } catch (Exception e) { return; }
        String[] v; // renderDistance, simulationDistance, particles(0 all,1 decreased,2 minimal), graphicsMode(0 fast,1 fancy), entityDistanceScaling, maxFps
        switch (profile) {
            case "Potato": v = new String[]{"5", "5", "2", "0", "0.5", "120"}; break;
            case "Low": v = new String[]{"8", "6", "1", "0", "0.75", "120"}; break;
            case "High FPS": v = new String[]{"10", "8", "2", "0", "0.75", "260"}; break;
            case "Quality": v = new String[]{"20", "12", "0", "1", "1.5", "144"}; break;
            default: v = new String[]{"12", "10", "0", "1", "1.0", "144"};
        }
        if (cfg.getProperty("rd", "").matches("\\d{1,2}")) v[0] = cfg.getProperty("rd");
        if (cfg.getProperty("fps", "").matches("\\d{1,3}")) v[5] = cfg.getProperty("fps");
        String[] keys = {"renderDistance", "simulationDistance", "particles", "graphicsMode", "entityDistanceScaling", "maxFps"};
        Path f = game.resolve("options.txt"); List<String> lines = Files.exists(f) ? new ArrayList<>(Files.readAllLines(f)) : new ArrayList<>();
        for (int i = 0; i < keys.length; i++) { boolean hit = false;
            for (int k = 0; k < lines.size(); k++) if (lines.get(k).startsWith(keys[i] + ":")) { lines.set(k, keys[i] + ":" + v[i]); hit = true; }
            if (!hit) lines.add(keys[i] + ":" + v[i]); }
        Files.write(f, lines);
    }

    static Process launch(String name, int ramGb) throws Exception {
        if (vanilla == null || !MC.equals(loaded)) install(LOG);
        Path game = inst(), natives = game.resolve("natives"); Files.createDirectories(natives);
        try { applyProfile(game); } catch (Exception e) { LOG.accept("Could not apply profile: " + e); }
        String cp = classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        String idxId = (String) M(vanilla.get("assetIndex")).get("id");
        Path virt = HOME.resolve("assets/virtual/" + idxId);
        String an = name, au = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes()).toString(), tok = "0", ut = "legacy";
        if (active != null) { LOG.accept("Refreshing Microsoft login..."); PROG.accept(0.5, "Signing in..."); refresh(active); an = active.name; au = active.uuid; tok = active.mcToken; ut = "msa"; }
        else LOG.accept("Offline mode: you cannot join online servers. Sign in on the Account page.");
        Map<String, String> v = new HashMap<>();
        v.put("${auth_player_name}", an); v.put("${version_name}", MC); v.put("${game_directory}", game.toString());
        v.put("${assets_root}", HOME.resolve("assets").toString()); v.put("${game_assets}", Files.isDirectory(virt) ? virt.toString() : HOME.resolve("assets").toString());
        v.put("${assets_index_name}", idxId); v.put("${auth_uuid}", au);
        v.put("${auth_access_token}", tok); v.put("${auth_session}", tok); v.put("${clientid}", CLIENT_ID); v.put("${auth_xuid}", "0"); v.put("${user_type}", ut);
        v.put("${user_properties}", "{}"); v.put("${profile_name}", "Vortex"); v.put("${version_type}", "Vortex"); v.put("${natives_directory}", natives.toString());
        v.put("${launcher_name}", "VortexLauncher"); v.put("${launcher_version}", "0.2"); v.put("${classpath}", cp);

        List<String> jvm = new ArrayList<>(), gm = new ArrayList<>();
        if (vanilla.containsKey("arguments")) { Map<String, Object> va = M(vanilla.get("arguments"));
            for (Object a : L(va.get("jvm"))) collect(a, v, jvm); for (Object a : L(va.get("game"))) collect(a, v, gm); }
        else { jvm.add("-Djava.library.path=" + natives); jvm.add("-cp"); jvm.add(cp); for (String s : ((String) vanilla.get("minecraftArguments")).split(" ")) collect(s, v, gm); }
        if (fabric != null && fabric.containsKey("arguments")) { Map<String, Object> fa = M(fabric.get("arguments"));
            if (fa.get("jvm") != null) for (Object a : L(fa.get("jvm"))) collect(a, v, jvm); if (fa.get("game") != null) for (Object a : L(fa.get("game"))) collect(a, v, gm); }
        List<String> cmd = new ArrayList<>(); cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xmx" + ramGb + "G"); cmd.add("-Xms" + Math.min(ramGb, 2) + "G");
        for (String o : new String[]{"-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50", "-XX:+UnlockExperimentalVMOptions", "-XX:G1NewSizePercent=20", "-XX:+DisableExplicitGC"}) cmd.add(o);
        for (String a : cfg.getProperty("jvm", "").trim().split("\\s+")) if (!a.isEmpty()) cmd.add(a);
        cmd.addAll(jvm); cmd.add((String) (fabric != null ? fabric : vanilla).get("mainClass")); cmd.addAll(gm);
        String gw = cfg.getProperty("w", ""), gh = cfg.getProperty("h", "");
        if (gw.matches("\\d{3,4}") && gh.matches("\\d{3,4}")) { cmd.add("--width"); cmd.add(gw); cmd.add("--height"); cmd.add(gh); }
        return new ProcessBuilder(cmd).directory(game.toFile()).redirectErrorStream(true).start();
    }
    static void collect(Object a, Map<String, String> v, List<String> out) {
        if (a instanceof String) { String s = (String) a; for (Map.Entry<String, String> e : v.entrySet()) s = s.replace(e.getKey(), e.getValue()); out.add(s); }
        else { Map<String, Object> m = M(a); if (!allowed(m.get("rules"))) return;
            Object val = m.get("value"); if (val instanceof List) for (Object x : L(val)) collect(x, v, out); else collect(val, v, out); }
    }
    static void popup(Component anchor, List<String> items, String cur, java.util.function.Consumer<String> pick) {
        JList<String> l = new JList<>(items.toArray(new String[0])); l.setBackground(new Color(0x15112A)); l.setForeground(TXT); l.setSelectionBackground(ACC);
        l.setFont(font(Font.PLAIN, 14)); l.setFixedCellHeight(28); l.setSelectedValue(cur, true);
        JScrollPane sp = new JScrollPane(l); sp.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 30))); sp.getVerticalScrollBar().setUI(new SB());
        sp.setPreferredSize(new Dimension(anchor.getWidth(), Math.min(items.size(), 9) * 28 + 2));
        JPopupMenu m = new JPopupMenu(); m.setBorder(null); m.add(sp);
        l.addMouseListener(new java.awt.event.MouseAdapter() { @Override public void mouseClicked(java.awt.event.MouseEvent e) { String s = l.getSelectedValue(); if (s != null) { m.setVisible(false); pick.accept(s); } } });
        m.show(anchor, 0, anchor.getHeight() + 4);
    }

    // ---------- Microsoft authentication (OAuth2 + PKCE -> Xbox Live -> XSTS -> Minecraft) ----------
    static final String CLIENT_ID = "b69dbd58-6ff8-4ac6-8365-b40a45b557ee", REDIRECT = "https://login.microsoftonline.com/common/oauth2/nativeclient",
        SCOPE = "XboxLive.signin offline_access", MS_AUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
        MS_TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    static class Account { String uuid = "", name = "", refresh = "", mcToken = ""; }
    static final List<Account> ACCOUNTS = new ArrayList<>();
    static Account active;
    static String pkceVerifier, pkceState;
    static Runnable ACC_UI = () -> {};
    static final Path ACC_FILE = HOME.resolve("accounts.properties");

    static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }
    static String b64u(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return HTTP.send(b.header("User-Agent", "VortexLauncher/0.3").build(), HttpResponse.BodyHandlers.ofString());
    }
    static Map<String, Object> postForm(String url, String... kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) { if (i > 0) sb.append('&'); sb.append(enc(kv[i])).append('=').append(enc(kv[i + 1])); }
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(sb.toString())));
        Map<String, Object> m = M(J.parse(r.body()));
        if (r.statusCode() != 200) throw new IOException("Microsoft sign-in failed: " + m.get("error") + " - " + m.get("error_description"));
        return m;
    }
    static Map<String, Object> postJson(String url, String body) throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
        Map<String, Object> m = r.body().trim().startsWith("{") ? M(J.parse(r.body())) : new HashMap<>();
        m.put("_status", (double) r.statusCode()); return m;
    }
    static int status(Map<String, Object> m) { return ((Number) m.get("_status")).intValue(); }

    /** Step 1-3: builds the PKCE authorize URL; the caller opens it in the system browser. */
    static String beginLogin() throws Exception {
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        byte[] v = new byte[64], s = new byte[16]; rnd.nextBytes(v); rnd.nextBytes(s);
        pkceVerifier = b64u(v); pkceState = b64u(s);
        String chal = b64u(java.security.MessageDigest.getInstance("SHA-256").digest(pkceVerifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        return MS_AUTH + "?client_id=" + CLIENT_ID + "&response_type=code&redirect_uri=" + enc(REDIRECT) + "&scope=" + enc(SCOPE)
            + "&code_challenge=" + chal + "&code_challenge_method=S256&state=" + pkceState + "&prompt=select_account&response_mode=query";
    }
    /** Step 4-10: takes the redirected address the user pasted, exchanges the code, runs the Xbox/Minecraft chain. */
    static Account completeLogin(String pasted) throws Exception {
        if (pkceVerifier == null) throw new IOException("Click \"Sign in with Microsoft\" first.");
        String q = pasted; int h = q.indexOf('#'); if (h >= 0) q = q.substring(0, h); int qi = q.indexOf('?'); if (qi >= 0) q = q.substring(qi + 1);
        Map<String, String> par = new HashMap<>();
        for (String kv : q.split("&")) { int i = kv.indexOf('='); if (i > 0) par.put(kv.substring(0, i), java.net.URLDecoder.decode(kv.substring(i + 1), java.nio.charset.StandardCharsets.UTF_8)); }
        if (par.containsKey("error")) throw new IOException("Microsoft: " + par.get("error") + " - " + par.get("error_description"));
        if (!pkceState.equals(par.get("state"))) throw new IOException("Sign-in state did not match. Click Sign in again.");
        String code = par.get("code"); if (code == null) throw new IOException("No code found. Paste the full address from the browser.");
        Map<String, Object> t = postForm(MS_TOKEN, "client_id", CLIENT_ID, "grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT, "code_verifier", pkceVerifier, "scope", SCOPE);
        pkceVerifier = null;
        return chain((String) t.get("access_token"), (String) t.get("refresh_token"));
    }
    static String xerr(long x) {
        if (x == 2148916233L) return "This Microsoft account has no Xbox profile. Create one at xbox.com, then try again.";
        if (x == 2148916235L) return "Xbox Live is not available in this account's country or region.";
        if (x == 2148916236L || x == 2148916237L) return "This account needs adult verification on Xbox.";
        if (x == 2148916238L) return "This is a child account. An adult must add it to a Microsoft family first.";
        return "Xbox sign-in was refused (XErr " + x + ").";
    }
    static Account chain(String msToken, String refresh) throws Exception {
        Map<String, Object> x = postJson("https://user.auth.xboxlive.com/user/authenticate",
            "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"d=" + msToken + "\"},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}");
        if (status(x) != 200) throw new IOException("Xbox Live sign-in failed (HTTP " + status(x) + ").");
        String xbl = (String) x.get("Token"), uhs = (String) M(L(M(x.get("DisplayClaims")).get("xui")).get(0)).get("uhs");
        Map<String, Object> xs = postJson("https://xsts.auth.xboxlive.com/xsts/authorize",
            "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + xbl + "\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}");
        if (status(xs) != 200) throw new IOException(xerr(xs.get("XErr") instanceof Number ? ((Number) xs.get("XErr")).longValue() : 0));
        Map<String, Object> mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", "{\"identityToken\":\"XBL3.0 x=" + uhs + ";" + xs.get("Token") + "\"}");
        if (status(mc) == 403) throw new IOException("Minecraft Services rejected this app (HTTP 403). The Vortex Azure app ID must be approved first: aka.ms/mce-reviewappid");
        if (status(mc) != 200) throw new IOException("Minecraft sign-in failed (HTTP " + status(mc) + ").");
        String tok = (String) mc.get("access_token");
        HttpResponse<String> er = send(HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/entitlements/mcstore")).header("Authorization", "Bearer " + tok).GET());
        if (er.statusCode() == 200) { boolean owns = false;
            for (Object o : L(M(J.parse(er.body())).get("items"))) { Object n = M(o).get("name"); if ("product_minecraft".equals(n) || "game_minecraft".equals(n)) owns = true; }
            if (!owns) throw new IOException("This account does not own Minecraft: Java Edition."); }
        HttpResponse<String> pr = send(HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile")).header("Authorization", "Bearer " + tok).GET());
        if (pr.statusCode() == 404) throw new IOException("No Minecraft Java profile found. Open the official launcher once to create your profile name.");
        if (pr.statusCode() != 200) throw new IOException("Could not read the Minecraft profile (HTTP " + pr.statusCode() + ").");
        Map<String, Object> p = M(J.parse(pr.body()));
        Account a = new Account(); a.uuid = (String) p.get("id"); a.name = (String) p.get("name"); a.refresh = refresh; a.mcToken = tok; return a;
    }
    static void refresh(Account a) throws Exception {
        Map<String, Object> t = postForm(MS_TOKEN, "client_id", CLIENT_ID, "grant_type", "refresh_token", "refresh_token", a.refresh, "scope", SCOPE);
        Account n = chain((String) t.get("access_token"), t.get("refresh_token") != null ? (String) t.get("refresh_token") : a.refresh);
        a.mcToken = n.mcToken; a.name = n.name; a.uuid = n.uuid; a.refresh = n.refresh; saveAccounts();
    }
    static void addAccount(Account a) { ACCOUNTS.removeIf(x -> x.uuid.equals(a.uuid)); ACCOUNTS.add(a); active = a; saveAccounts(); }

    // Refresh tokens are stored encrypted: Windows DPAPI (current user) via PowerShell, AES-GCM with a local key file elsewhere. No passwords are ever stored.
    static String ps(String script, String stdin) throws Exception {
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start();
        p.getOutputStream().write(stdin.getBytes()); p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes()).trim(); if (p.waitFor() != 0) throw new IOException("DPAPI failed: " + out); return out;
    }
    static final String DP = "Add-Type -AssemblyName System.Security; $b=[Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()); [Convert]::ToBase64String([Security.Cryptography.ProtectedData]::";
    static byte[] key() throws Exception {
        Path kf = HOME.resolve(".vortex.key");
        if (!Files.exists(kf)) { byte[] k = new byte[32]; new java.security.SecureRandom().nextBytes(k); Files.createDirectories(HOME); Files.write(kf, k);
            kf.toFile().setReadable(false, false); kf.toFile().setReadable(true, true); kf.toFile().setWritable(false, false); kf.toFile().setWritable(true, true); }
        return Files.readAllBytes(kf);
    }
    static String protect(String plain) throws Exception {
        byte[] data = plain.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (osName().equals("windows")) return ps(DP + "Protect($b,$null,'CurrentUser'))", Base64.getEncoder().encodeToString(data));
        byte[] iv = new byte[12]; new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key(), "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(data), out = new byte[iv.length + ct.length]; System.arraycopy(iv, 0, out, 0, iv.length); System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }
    static String unprotect(String s) throws Exception {
        if (osName().equals("windows")) return new String(Base64.getDecoder().decode(ps(DP + "Unprotect($b,$null,'CurrentUser'))", s)), java.nio.charset.StandardCharsets.UTF_8);
        byte[] in = Base64.getDecoder().decode(s);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key(), "AES"), new javax.crypto.spec.GCMParameterSpec(128, in, 0, 12));
        return new String(c.doFinal(in, 12, in.length - 12), java.nio.charset.StandardCharsets.UTF_8);
    }
    static void saveAccounts() {
        try { Properties p = new Properties(); int n = 0;
            for (Account a : ACCOUNTS) { p.setProperty("a" + n + ".uuid", a.uuid); p.setProperty("a" + n + ".name", a.name); p.setProperty("a" + n + ".refresh", protect(a.refresh)); n++; }
            p.setProperty("count", "" + n); if (active != null) p.setProperty("active", active.uuid);
            Files.createDirectories(HOME); try (OutputStream o = Files.newOutputStream(ACC_FILE)) { p.store(o, "Vortex accounts (tokens encrypted)"); }
        } catch (Exception e) { LOG.accept("Could not save accounts: " + e.getMessage()); }
    }
    static void loadAccounts() {
        try (InputStream i = Files.newInputStream(ACC_FILE)) {
            Properties p = new Properties(); p.load(i); int n = Integer.parseInt(p.getProperty("count", "0"));
            for (int k = 0; k < n; k++) { try { Account a = new Account(); a.uuid = p.getProperty("a" + k + ".uuid"); a.name = p.getProperty("a" + k + ".name"); a.refresh = unprotect(p.getProperty("a" + k + ".refresh"));
                ACCOUNTS.add(a); if (a.uuid.equals(p.getProperty("active"))) active = a; } catch (Exception e) { /* unreadable entry: skip */ } }
            if (active == null && !ACCOUNTS.isEmpty()) active = ACCOUNTS.get(0);
        } catch (Exception e) { /* no accounts yet */ }
    }
    static String html(String s) { return "<html><div style='width:330px'>" + s.replace("<", "&lt;") + "</div></html>"; }

    // ---------- mods: local, Modrinth, CurseForge ----------
    static boolean FX = true;
    static final String[][] THEMES = {{"7C5CFF", "38D6FF"}, {"FF5C93", "FFB86B"}, {"2BD98B", "38D6FF"}, {"FFB020", "FF6B6B"}, {"3B82F6", "22D3EE"}};
    static void applyTheme(int i) { ACC = new Color(Integer.parseInt(THEMES[i][0], 16)); ACC2 = new Color(Integer.parseInt(THEMES[i][1], 16)); }
    static String cut(String s) { return s == null ? "" : s.length() > 62 ? s.substring(0, 61) + "\u2026" : s; }
    static String[] readMeta(Path jar) {
        String fb = jar.getFileName().toString();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry en = z.getEntry("fabric.mod.json"); if (en == null) return new String[]{"", fb, "", ""};
            Map<String, Object> m = M(J.parse(new String(z.getInputStream(en).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            return new String[]{String.valueOf(m.getOrDefault("id", "")), String.valueOf(m.getOrDefault("name", fb)), String.valueOf(m.getOrDefault("version", "")), String.valueOf(m.getOrDefault("description", ""))};
        } catch (Exception ex) { return new String[]{"", fb, "", ""}; }
    }
    static Set<String> disabledSet() { Set<String> s = new TreeSet<>(); for (String x : cfg.getProperty("disabledMods", "").split(",")) if (!x.isEmpty()) s.add(x); return s; }
    static void moveMod(Path p, boolean disable, String id) {
        try { Path to = inst().resolve(disable ? "mods-disabled" : "mods"); Files.createDirectories(to); Files.move(p, to.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            if (!id.isEmpty()) { Set<String> s = disabledSet(); if (disable) s.add(id); else s.remove(id); cfg.setProperty("disabledMods", String.join(",", s)); save(); }
        } catch (Exception ex) { LOG.accept("Could not move mod: " + ex.getMessage()); }
    }
    /** Two jars with the same Fabric mod id crash the game, so keep only the newest. */
    static void dedupeAll(Path mods, java.util.function.Consumer<String> log) {
        Map<String, Path> best = new HashMap<>();
        try (Stream<Path> s = Files.list(mods)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".jar"))::iterator) {
                String id = readMeta(p)[0]; if (id.isEmpty()) continue; Path o = best.get(id);
                if (o == null) { best.put(id, p); continue; }
                Path del = Files.getLastModifiedTime(p).compareTo(Files.getLastModifiedTime(o)) > 0 ? o : p; Files.deleteIfExists(del); log.accept("Removed duplicate: " + del.getFileName());
                if (del == o) best.put(id, p);
            }
        } catch (Exception ex) { /* best effort */ }
    }
    static List<String[]> searchModrinth(String q) throws Exception {
        String facets = "[[\"versions:" + MC + "\"],[\"categories:fabric\"],[\"project_type:mod\"]]";
        Map<String, Object> r = M(J.parse(get("https://api.modrinth.com/v2/search?query=" + enc(q) + "&limit=20&facets=" + enc(facets))));
        List<String[]> out = new ArrayList<>();
        for (Object o : L(r.get("hits"))) { Map<String, Object> h = M(o); out.add(new String[]{(String) h.get("title"), (String) h.get("description"), (String) h.get("slug")}); }
        return out;
    }
    static String cfKey() { try { String s = cfg.getProperty("cfkey", ""); return s.isEmpty() ? "" : unprotect(s); } catch (Exception ex) { return ""; } }
    static String cf(String path) throws Exception {
        String key = cfKey(); if (key.isEmpty()) throw new IOException("Add your CurseForge API key on the Advanced page.");
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("https://api.curseforge.com" + path)).header("x-api-key", key).header("Accept", "application/json").GET());
        if (r.statusCode() != 200) throw new IOException("CurseForge returned HTTP " + r.statusCode()); return r.body();
    }
    static List<String[]> searchCurse(String q) throws Exception {
        Map<String, Object> r = M(J.parse(cf("/v1/mods/search?gameId=432&classId=6&gameVersion=" + MC + "&modLoaderType=4&sortField=2&sortOrder=desc&pageSize=20&searchFilter=" + enc(q))));
        List<String[]> out = new ArrayList<>();
        for (Object o : L(r.get("data"))) { Map<String, Object> h = M(o); out.add(new String[]{(String) h.get("name"), (String) h.get("summary"), String.valueOf(((Number) h.get("id")).longValue())}); }
        return out;
    }
    static void installCurse(String id, Path mods, Set<String> seen, List<String> got, java.util.function.Consumer<String> log) throws Exception {
        if (!seen.add("cf:" + id)) return;
        List<Object> data = L(M(J.parse(cf("/v1/mods/" + id + "/files?gameVersion=" + MC + "&modLoaderType=4&pageSize=10"))).get("data"));
        if (data.isEmpty()) { log.accept("No compatible CurseForge file for project " + id + " on " + MC); return; }
        Map<String, Object> f = M(data.get(0));
        for (Object o : data) if (((Number) M(o).get("releaseType")).intValue() == 1) { f = M(o); break; }
        for (Object d : L(f.get("dependencies"))) { Map<String, Object> dm = M(d);
            if (((Number) dm.get("relationType")).intValue() == 3) installCurse(String.valueOf(((Number) dm.get("modId")).longValue()), mods, seen, got, log); }
        if (f.get("downloadUrl") == null) { log.accept("The author of " + f.get("fileName") + " disabled third-party downloads. Get it from its CurseForge page."); return; }
        String sha = null; for (Object h : L(f.get("hashes"))) if (((Number) M(h).get("algo")).intValue() == 1) sha = (String) M(h).get("value");
        String fn = (String) f.get("fileName"); fetch((String) f.get("downloadUrl"), mods.resolve(fn), sha); got.add(fn); log.accept("CurseForge mod: " + fn);
    }
    static void importMrpack(Path pack, java.util.function.Consumer<String> log) throws Exception {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(pack.toFile())) {
            java.util.zip.ZipEntry ie = z.getEntry("modrinth.index.json"); if (ie == null) throw new IOException("Not a valid .mrpack file.");
            Map<String, Object> idx = M(J.parse(new String(z.getInputStream(ie).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            Object need = M(idx.get("dependencies")).get("minecraft");
            if (need != null && !need.equals(MC)) log.accept("Warning: this pack is for Minecraft " + need + " but " + MC + " is selected.");
            Path base = inst();
            for (Object o : L(idx.get("files"))) { Map<String, Object> f = M(o); Map<String, Object> env = M(f.get("env"));
                if (env != null && "unsupported".equals(env.get("client"))) continue;
                String path = (String) f.get("path"); Path dest = base.resolve(path).normalize(); if (!dest.startsWith(base)) continue;
                fetch((String) L(f.get("downloads")).get(0), dest, (String) M(f.get("hashes")).get("sha1")); log.accept("Pack file: " + path); }
            for (java.util.zip.ZipEntry en : Collections.list(z.entries())) {
                String n = en.getName(), rel = n.startsWith("overrides/") ? n.substring(10) : n.startsWith("client-overrides/") ? n.substring(17) : null;
                if (rel == null || rel.isEmpty() || en.isDirectory()) continue;
                Path dest = base.resolve(rel).normalize(); if (!dest.startsWith(base)) continue; Files.createDirectories(dest.getParent());
                try (InputStream in = z.getInputStream(en)) { Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }
    static JLabel tag(String t, int x, int y) { JLabel l = label(t, 11, Font.BOLD, DIM); l.setBounds(x, y, 330, 16); return l; }
    static void bind(JTextField f, String key) {
        f.setText(cfg.getProperty(key, ""));
        f.addKeyListener(new java.awt.event.KeyAdapter() { @Override public void keyReleased(java.awt.event.KeyEvent e) { cfg.setProperty(key, f.getText().trim()); save(); } });
    }
    static JPanel row(String title, String sub, String t1, Runnable a1, String t2, Runnable a2) {
        JPanel r = new JPanel(null) { @Override protected void paintComponent(Graphics g0) { Graphics2D g = aa(g0); g.setColor(new Color(255, 255, 255, 10)); g.fillRoundRect(0, 2, getWidth(), getHeight() - 4, 14, 14); g.dispose(); } };
        r.setOpaque(false); r.setPreferredSize(new Dimension(556, 56)); r.setMaximumSize(new Dimension(556, 56));
        JLabel t = label(cut(title), 14, Font.BOLD, TXT); t.setBounds(14, 8, 330, 20); JLabel s = label(sub, 12, Font.PLAIN, DIM); s.setBounds(14, 29, 330, 18); r.add(t); r.add(s);
        if (t1 != null) { Btn b = new Btn(t1, false); b.setFont(font(Font.PLAIN, 12)); b.setBounds(a2 == null ? 450 : 350, 11, 92, 34); b.addActionListener(e -> a1.run()); r.add(b); }
        if (t2 != null) { Btn b = new Btn(t2, false); b.setFont(font(Font.PLAIN, 12)); b.setBounds(450, 11, 92, 34); b.addActionListener(e -> a2.run()); r.add(b); }
        return r;
    }
    static class Swatch extends JComponent {
        final Color a, b; boolean sel;
        Swatch(Color a, Color b) { this.a = a; this.b = b; setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = aa(g0); g.setPaint(new GradientPaint(0, 0, a, 36, 36, b)); g.fillOval(4, 4, 28, 28);
            if (sel) { g.setColor(Color.WHITE); g.setStroke(new BasicStroke(2f)); g.drawOval(1, 1, 34, 34); } g.dispose();
        }
    }

    // ---------- UI ----------
    static Font font(int style, float size) { return new Font("Segoe UI", style, 14).deriveFont(style, size); }
    static Graphics2D aa(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }
    static java.awt.image.BufferedImage LOGO;
    static void loadLogo() {
        for (Path d : new Path[]{appDir(), Paths.get("."), HOME}) { Path p = d.resolve("vortex-logo.png");
            if (Files.exists(p)) { try { LOGO = javax.imageio.ImageIO.read(p.toFile()); return; } catch (Exception e) {} } }
    }
    static void drawLogo(Graphics2D g, int cx, int cy, int r, float width) {
        if (LOGO != null) { int d = (int) (r * 2.6); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(LOGO, cx - d / 2, cy - d / 2, d, d, null); return; }
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D p = new Path2D.Double();
        for (int i = 0; i <= 200; i++) { double t = i / 200.0, a = t * 3.2 * Math.PI * 2, rr = r * (0.1 + 0.9 * t);
            double x = cx + Math.cos(a) * rr, y = cy + Math.sin(a) * rr; if (i == 0) p.moveTo(x, y); else p.lineTo(x, y); }
        g.setPaint(new GradientPaint(cx - r, cy - r, ACC2, cx + r, cy + r, ACC)); g.draw(p);
    }
    static void glow(Graphics2D g, int cx, int cy, int r, Color c, int a) {
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), r, new float[]{0f, 1f},
            new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), a), new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
        g.fillRect(cx - r, cy - r, r * 2, r * 2);
    }
    static JLabel label(String t, int size, int style, Color c) { JLabel l = new JLabel(t); l.setFont(font(style, size)); l.setForeground(c); return l; }

    static class Bg extends JPanel {
        java.awt.image.BufferedImage cache; int cw, ch; final float[][] ps = new float[28][5]; final long t0 = System.nanoTime();
        Bg() {
            setLayout(null); Random r = new Random(7);
            for (float[] p : ps) { p[0] = r.nextFloat(); p[1] = r.nextFloat(); p[2] = 1.5f + r.nextFloat() * 3f; p[3] = 0.02f + r.nextFloat() * 0.05f; p[4] = 0.15f + r.nextFloat() * 0.5f; }
            new javax.swing.Timer(33, e -> repaint()).start();
        }
        void build(int w, int h) {
            cache = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB); Graphics2D g = aa(cache.getGraphics());
            g.setColor(BG); g.fillRect(0, 0, w, h); glow(g, w - 140, 90, 440, ACC, 75); glow(g, 330, h + 60, 400, ACC2, 38); glow(g, 260, -40, 300, ACC, 30);
            g.setColor(new Color(10, 8, 20, 215)); g.fillRect(0, 0, 200, h); g.setColor(new Color(255, 255, 255, 16)); g.drawLine(200, 0, 200, h);
            drawLogo(g, 100, 64, 34, 4f); g.dispose(); cw = w; ch = h;
        }
        @Override protected void paintComponent(Graphics g0) {
            int w = getWidth(), h = getHeight(); if (cache == null || cw != w || ch != h) build(w, h);
            Graphics2D g = aa(g0); g.drawImage(cache, 0, 0, null); double t = (System.nanoTime() - t0) / 1e9;
            Graphics2D rot = (Graphics2D) g.create(); rot.setClip(201, 0, w - 201, h); rot.rotate(t * 0.1, w - 200, h - 190);
            rot.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.10f)); drawLogo(rot, w - 200, h - 190, 160, 10f); rot.dispose();
            for (float[] p : FX ? ps : new float[0][]) {
                double yy = ((p[1] - t * p[3] * 0.08) % 1 + 1) % 1, xx = (((p[0] + Math.sin(t * 0.4 + p[4] * 9) * 0.015) % 1) + 1) % 1;
                g.setColor(new Color(180, 170, 255, (int) (25 + p[4] * 90))); g.fill(new Ellipse2D.Float(200 + (float) xx * (w - 200), (float) yy * h, p[2], p[2]));
            }
            g.dispose();
        }
    }
    static class Card extends JPanel {
        Card() { setOpaque(false); setLayout(null); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = aa(g0);
            g.setColor(new Color(255, 255, 255, 12)); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 22, 22);
            g.setColor(new Color(255, 255, 255, 24)); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 22, 22); g.dispose();
        }
    }
    static class Btn extends JButton {
        float h; boolean primary, sel; final javax.swing.Timer t;
        Btn(String s, boolean primary) {
            super(s); this.primary = primary;
            setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false);
            setFont(font(Font.BOLD, 15)); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            t = new javax.swing.Timer(15, e -> { float target = getModel().isRollover() ? 1f : 0f; h += (target - h) * 0.25f;
                if (Math.abs(target - h) < 0.02f) { h = target; ((javax.swing.Timer) e.getSource()).stop(); } repaint(); });
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { t.start(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { t.start(); } });
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = aa(g0); int w = getWidth(), hh = getHeight();
            if (primary) { g.setPaint(new GradientPaint(0, 0, ACC, w, hh, ACC2)); g.fillRoundRect(0, 0, w, hh, 18, 18);
                g.setColor(new Color(255, 255, 255, (int) (h * 40))); g.fillRoundRect(0, 0, w, hh, 18, 18); }
            else { g.setColor(new Color(255, 255, 255, (int) (sel ? 30 : 10 + h * 22))); g.fillRoundRect(0, 0, w, hh, 18, 18); }
            if (getModel().isPressed()) { g.setColor(new Color(0, 0, 0, 55)); g.fillRoundRect(0, 0, w, hh, 18, 18); }
            g.setColor(!isEnabled() ? DIM : (primary || sel) ? Color.WHITE : TXT); g.setFont(getFont());
            FontMetrics m = g.getFontMetrics(); g.drawString(getText(), (w - m.stringWidth(getText())) / 2, (hh + m.getAscent() - m.getDescent()) / 2); g.dispose();
        }
    }
    static class Bar extends JComponent {
        double shown, target; String text = "Ready";
        final javax.swing.Timer tm = new javax.swing.Timer(16, e -> { shown += (target - shown) * 0.12;
            if (Math.abs(target - shown) < 0.002) { shown = target; ((javax.swing.Timer) e.getSource()).stop(); } repaint(); });
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = aa(g0); int w = getWidth(), h = 12;
            g.setFont(font(Font.PLAIN, 12)); g.setColor(DIM); g.drawString(text, 0, 12);
            g.setColor(new Color(255, 255, 255, 20)); g.fillRoundRect(0, 20, w, h, h, h);
            int fw = (int) (w * shown); if (fw > 0) { g.setPaint(new GradientPaint(0, 0, ACC, w, 0, ACC2)); g.fillRoundRect(0, 20, Math.max(fw, h), h, h, h); }
            g.dispose();
        }
        void set(double v, String t) { SwingUtilities.invokeLater(() -> { target = v; if (t != null) text = t; if (!tm.isRunning()) tm.start(); repaint(); }); }
    }
    static class Slide extends JComponent {
        int v = 4, min = 2, max = 16; Runnable changed;
        Slide() {
            java.awt.event.MouseAdapter ma = new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) { pick(e.getX()); }
                @Override public void mouseDragged(java.awt.event.MouseEvent e) { pick(e.getX()); } };
            addMouseListener(ma); addMouseMotionListener(ma); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        void pick(int x) { v = Math.max(min, Math.min(max, min + (int) Math.round((x - 8.0) / (getWidth() - 16) * (max - min)))); repaint(); if (changed != null) changed.run(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = aa(g0); int w = getWidth(), cy = getHeight() / 2; int x = 8 + (int) ((w - 16) * (v - min) / (double) (max - min));
            g.setColor(new Color(255, 255, 255, 24)); g.fillRoundRect(8, cy - 3, w - 16, 6, 6, 6);
            g.setPaint(new GradientPaint(0, 0, ACC, w, 0, ACC2)); g.fillRoundRect(8, cy - 3, x - 8, 6, 6, 6);
            g.setColor(Color.WHITE); g.fillOval(x - 8, cy - 8, 16, 16); g.dispose();
        }
    }
    static class Chip extends JComponent {
        final String s; final Color c;
        Chip(String s, Color c) { this.s = s; this.c = c; setSize(new JLabel().getFontMetrics(font(Font.BOLD, 12)).stringWidth(s) + 24, 26); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = aa(g0); g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 38)); g.fillRoundRect(0, 0, getWidth(), getHeight(), 26, 26);
            g.setColor(c); g.setFont(font(Font.BOLD, 12)); FontMetrics m = g.getFontMetrics(); g.drawString(s, 12, (getHeight() + m.getAscent() - m.getDescent()) / 2); g.dispose();
        }
    }
    static class Avatar extends JComponent {
        String name = "Player", sub = "Offline account";
        void set(String n) { name = n.isEmpty() ? "Player" : n; repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = aa(g0); g.setPaint(new GradientPaint(0, 0, ACC2, 44, 44, ACC)); g.fillOval(4, 6, 44, 44);
            g.setColor(Color.WHITE); g.setFont(font(Font.BOLD, 20)); String ini = name.substring(0, 1).toUpperCase(); FontMetrics m = g.getFontMetrics();
            g.drawString(ini, 4 + (44 - m.stringWidth(ini)) / 2, 6 + (44 + m.getAscent() - m.getDescent()) / 2);
            g.setColor(TXT); g.setFont(font(Font.BOLD, 14)); g.drawString(name.length() > 12 ? name.substring(0, 12) + "\u2026" : name, 58, 28);
            g.setColor(DIM); g.setFont(font(Font.PLAIN, 11)); g.drawString(sub, 58, 44); g.dispose();
        }
    }
    static class SB extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override protected void configureScrollBarColors() { thumbColor = new Color(255, 255, 255, 40); trackColor = new Color(0, 0, 0, 0); }
        @Override protected JButton createDecreaseButton(int o) { return zero(); }
        @Override protected JButton createIncreaseButton(int o) { return zero(); }
        JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {}
        @Override protected void paintThumb(Graphics g0, JComponent c, Rectangle r) { Graphics2D g = aa(g0); g.setColor(thumbColor); g.fillRoundRect(r.x + 2, r.y, r.width - 4, r.height, 8, 8); g.dispose(); }
    }
    static JTextField field(String text) {
        JTextField f = new JTextField(text) { @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = aa(g0); g.setColor(new Color(255, 255, 255, 16)); g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14); g.dispose(); super.paintComponent(g0); } };
        f.setOpaque(false); f.setForeground(TXT); f.setCaretColor(TXT); f.setFont(font(Font.PLAIN, 15)); f.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14)); return f;
    }

    static final Path CFG = HOME.resolve("settings.properties");
    static final Properties cfg = new Properties();
    static void load() { try (InputStream i = Files.newInputStream(CFG)) { cfg.load(i); } catch (Exception e) {} }
    static void save() { try { Files.createDirectories(HOME); try (OutputStream o = Files.newOutputStream(CFG)) { cfg.store(o, "Vortex"); } } catch (Exception e) {} }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on"); System.setProperty("swing.aatext", "true"); load(); loadAccounts(); loadLogo(); FX = !"false".equals(cfg.getProperty("fx")); try { applyTheme(Integer.parseInt(cfg.getProperty("theme", "0"))); } catch (Exception ignored) {} MC = cfg.getProperty("version", MC); profile = cfg.getProperty("profile", profile); useVortex = !"false".equals(cfg.getProperty("vortex"));
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Vortex Launcher"); f.setUndecorated(true); if (LOGO != null) f.setIconImage(LOGO); f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            Bg bg = new Bg(); bg.setPreferredSize(new Dimension(900, 580)); f.setContentPane(bg);
            Btn wx = new Btn("\u00d7", false), wm = new Btn("\u2013", false); wx.setFont(font(Font.PLAIN, 18)); wm.setFont(font(Font.PLAIN, 18));
            wx.setBounds(858, 14, 28, 28); wm.setBounds(822, 14, 28, 28); wx.addActionListener(e -> System.exit(0)); wm.addActionListener(e -> f.setState(Frame.ICONIFIED)); bg.add(wx); bg.add(wm);
            java.awt.event.MouseAdapter drag = new java.awt.event.MouseAdapter() { int px, py;
                @Override public void mousePressed(java.awt.event.MouseEvent e) { px = e.getX(); py = e.getY(); }
                @Override public void mouseDragged(java.awt.event.MouseEvent e) { if (py < 60) f.setLocation(e.getXOnScreen() - px, e.getYOnScreen() - py); } };
            bg.addMouseListener(drag); bg.addMouseMotionListener(drag);

            JLabel brand = label("VORTEX", 22, Font.BOLD, TXT); brand.setBounds(0, 108, 200, 30); brand.setHorizontalAlignment(SwingConstants.CENTER); bg.add(brand);
            Btn navPlay = new Btn("Play", false), navSet = new Btn("Settings", false); navPlay.sel = true;
            navPlay.setBounds(24, 180, 152, 42); navSet.setBounds(24, 230, 152, 42); bg.add(navPlay); bg.add(navSet);
            Btn navAcc = new Btn("Account", false); navAcc.setBounds(24, 280, 152, 42); bg.add(navAcc);
            Btn navMods = new Btn("Mods", false), navAdv = new Btn("Advanced", false); navMods.setBounds(24, 330, 152, 42); navAdv.setBounds(24, 380, 152, 42); bg.add(navMods); bg.add(navAdv);
            JLabel ver = label("v0.1  \u00b7  Minecraft " + MC, 12, Font.PLAIN, DIM); ver.setBounds(0, 540, 200, 20); ver.setHorizontalAlignment(SwingConstants.CENTER); bg.add(ver);

            JPanel play = new JPanel(null), set = new JPanel(null);
            for (JPanel p : new JPanel[]{play, set}) { p.setOpaque(false); p.setBounds(200, 0, 700, 580); bg.add(p); }
            set.setVisible(false);

            JLabel t = label("Minecraft " + MC, 40, Font.BOLD, TXT); t.setBounds(48, 56, 600, 54); play.add(t);
            JLabel st = label("Fabric  \u00b7  Vortex Client", 16, Font.PLAIN, DIM); st.setBounds(50, 114, 600, 24); play.add(st);
            Btn vB = new Btn("Version  " + MC + " \u25be", false), pB = new Btn("Profile  " + profile + " \u25be", false);
            vB.setFont(font(Font.PLAIN, 13)); pB.setFont(font(Font.PLAIN, 13)); vB.setBounds(48, 148, 200, 32); pB.setBounds(256, 148, 200, 32); play.add(vB); play.add(pB);
            Btn playB = new Btn("PLAY", true), instB = new Btn("Install / Update", false);
            playB.setFont(font(Font.BOLD, 20)); playB.setBounds(48, 200, 300, 64); instB.setBounds(364, 200, 170, 64); play.add(playB); play.add(instB);
            Bar bar = new Bar(); bar.setBounds(48, 286, 604, 40); play.add(bar);
            Card lc = new Card(); lc.setBounds(48, 346, 604, 196); play.add(lc);
            JTextArea log = new JTextArea(); log.setEditable(false); log.setOpaque(false); log.setForeground(DIM); log.setFont(new Font("Consolas", Font.PLAIN, 12)); log.setLineWrap(true);
            JScrollPane sp = new JScrollPane(log); sp.setBorder(null); sp.setOpaque(false); sp.getViewport().setOpaque(false);
            sp.getVerticalScrollBar().setUI(new SB()); sp.setBounds(14, 12, 576, 172); lc.add(sp);

            JLabel sh = label("Settings", 40, Font.BOLD, TXT); sh.setBounds(48, 56, 600, 54); set.add(sh);
            Card sc = new Card(); sc.setBounds(48, 130, 604, 270); set.add(sc);
            JLabel l1 = label("OFFLINE USERNAME (DEV ONLY)", 11, Font.BOLD, DIM); l1.setBounds(24, 20, 200, 16); sc.add(l1);
            JTextField name = field(cfg.getProperty("name", "Player")); name.setBounds(24, 42, 556, 42); sc.add(name);
            Slide ram = new Slide(); ram.v = Integer.parseInt(cfg.getProperty("ram", "4"));
            JLabel l2 = label("MEMORY  " + ram.v + " GB", 11, Font.BOLD, DIM); l2.setBounds(24, 112, 300, 16); sc.add(l2);
            ram.setBounds(24, 138, 556, 30); sc.add(ram);
            JLabel hint = label("4\u20136 GB is plenty for Vortex. Higher values can cause longer GC pauses.", 12, Font.PLAIN, DIM); hint.setBounds(24, 184, 556, 18); sc.add(hint);
            Btn vt = new Btn("Vortex mod: " + (useVortex ? "ON" : "OFF"), false); vt.setFont(font(Font.PLAIN, 13)); vt.setBounds(24, 214, 220, 34); sc.add(vt);
            vt.addActionListener(e -> { useVortex = !useVortex; cfg.setProperty("vortex", "" + useVortex); save(); vt.setText("Vortex mod: " + (useVortex ? "ON" : "OFF")); });
            ram.changed = () -> { l2.setText("MEMORY  " + ram.v + " GB"); cfg.setProperty("ram", "" + ram.v); save(); };
            Avatar av = new Avatar(); av.setBounds(16, 468, 168, 56); av.set(name.getText()); bg.add(av);
            name.addKeyListener(new java.awt.event.KeyAdapter() { @Override public void keyReleased(java.awt.event.KeyEvent e) { if (active == null) ACC_UI.run(); } });

            JPanel acc = new JPanel(null); acc.setOpaque(false); acc.setBounds(200, 0, 700, 580); acc.setVisible(false); bg.add(acc);
            JLabel ah = label("Account", 40, Font.BOLD, TXT); ah.setBounds(48, 56, 600, 54); acc.add(ah);
            Card ac = new Card(); ac.setBounds(48, 130, 604, 160); acc.add(ac);
            JLabel an = label("Not signed in", 22, Font.BOLD, TXT); an.setBounds(24, 18, 556, 30); ac.add(an);
            JLabel au = label(" ", 12, Font.PLAIN, DIM); au.setBounds(24, 52, 556, 18); ac.add(au);
            Btn si = new Btn("Sign in with Microsoft", true), sw = new Btn("Switch", false), so = new Btn("Sign out", false);
            si.setBounds(24, 96, 240, 44); sw.setBounds(276, 96, 140, 44); so.setBounds(428, 96, 140, 44); ac.add(si); ac.add(sw); ac.add(so);
            Card pc = new Card(); pc.setBounds(48, 306, 604, 190); pc.setVisible(false); acc.add(pc);
            JLabel pi = label("<html>Sign in in your browser. When it shows a blank page, copy the <b>full address</b> from the address bar and paste it here.</html>", 13, Font.PLAIN, DIM); pi.setBounds(24, 14, 556, 40); pc.add(pi);
            JTextField paste = field(""); paste.setBounds(24, 64, 556, 42); pc.add(paste);
            Btn fin = new Btn("Complete sign-in", true); fin.setBounds(24, 124, 200, 44); pc.add(fin);
            JLabel pst = label(" ", 12, Font.PLAIN, DIM); pst.setBounds(236, 120, 344, 56); pc.add(pst);
            ACC_UI = () -> SwingUtilities.invokeLater(() -> {
                if (active != null) { an.setText(active.name); au.setText("Microsoft account  \u00b7  " + active.uuid); av.sub = "Microsoft account"; av.set(active.name); }
                else { an.setText("Not signed in"); au.setText("Offline mode: online servers are unavailable until you sign in."); av.sub = "Offline account"; av.set(name.getText().trim()); }
                sw.setEnabled(ACCOUNTS.size() > 1); so.setEnabled(active != null); });
            si.addActionListener(e -> { try { String url = beginLogin(); pc.setVisible(true); pst.setText(html("Waiting for you to sign in..."));
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(URI.create(url)); else pst.setText(html("Open this link in your browser: " + url));
                } catch (Exception ex) { pst.setText(html(ex.getMessage())); } });
            fin.addActionListener(e -> new Thread(() -> { SwingUtilities.invokeLater(() -> { fin.setEnabled(false); pst.setText(html("Signing in...")); });
                try { Account a = completeLogin(paste.getText().trim()); addAccount(a); LOG.accept("Signed in as " + a.name);
                    SwingUtilities.invokeLater(() -> { paste.setText(""); pc.setVisible(false); ACC_UI.run(); }); }
                catch (Exception ex) { SwingUtilities.invokeLater(() -> pst.setText(html(ex.getMessage()))); }
                finally { SwingUtilities.invokeLater(() -> fin.setEnabled(true)); } }).start());
            sw.addActionListener(e -> popup(sw, ACCOUNTS.stream().map(a -> a.name).collect(Collectors.toList()), active == null ? "" : active.name,
                nm -> { for (Account a : ACCOUNTS) if (a.name.equals(nm)) active = a; saveAccounts(); ACC_UI.run(); }));
            so.addActionListener(e -> { if (active != null) { ACCOUNTS.remove(active); active = ACCOUNTS.isEmpty() ? null : ACCOUNTS.get(0); saveAccounts(); ACC_UI.run(); } });
            ACC_UI.run();

            // ---- Mods page ----
            JPanel mp = new JPanel(null); mp.setOpaque(false); mp.setBounds(200, 0, 700, 580); mp.setVisible(false); bg.add(mp);
            JLabel mh = label("Mods", 40, Font.BOLD, TXT); mh.setBounds(48, 40, 400, 54); mp.add(mh);
            Btn tInst = new Btn("Installed", false), tBrow = new Btn("Browse", false), imp = new Btn("Import", false), opn = new Btn("Folder", false);
            tInst.sel = true; tInst.setBounds(48, 104, 110, 34); tBrow.setBounds(166, 104, 110, 34); imp.setBounds(484, 104, 80, 34); opn.setBounds(572, 104, 80, 34);
            for (Btn b : new Btn[]{tInst, tBrow, imp, opn}) { b.setFont(font(Font.PLAIN, 13)); mp.add(b); }
            Card mc = new Card(); mc.setBounds(48, 148, 604, 392); mp.add(mc);
            JTextField msearch = field(""); msearch.setBounds(14, 12, 300, 38); mc.add(msearch);
            Btn msrc = new Btn("Modrinth", false), mgo = new Btn("Filter", true); msrc.setFont(font(Font.PLAIN, 13)); msrc.setBounds(322, 12, 120, 38); mgo.setBounds(450, 12, 140, 38); mc.add(msrc); mc.add(mgo);
            JPanel list = new JPanel(); list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS)); list.setOpaque(false);
            JScrollPane ms2 = new JScrollPane(list); ms2.setBorder(null); ms2.setOpaque(false); ms2.getViewport().setOpaque(false);
            ms2.getVerticalScrollBar().setUI(new SB()); ms2.getVerticalScrollBar().setUnitIncrement(16); ms2.setBounds(10, 60, 584, 298); mc.add(ms2);
            JLabel mst = label(" ", 12, Font.PLAIN, DIM); mst.setBounds(14, 364, 570, 18); mc.add(mst);
            java.util.function.Consumer<String> mlog = m -> { LOG.accept(m); SwingUtilities.invokeLater(() -> mst.setText(cut(m))); };
            boolean[] browse = {false, false}; Runnable[] reload = new Runnable[1];
            reload[0] = () -> {
                if (!browse[0]) {
                    list.removeAll(); String q = msearch.getText().trim().toLowerCase(); Path md = inst().resolve("mods"), dd = inst().resolve("mods-disabled");
                    for (int pass = 0; pass < 2; pass++) { Path d = pass == 0 ? md : dd; if (!Files.isDirectory(d)) continue; boolean on = pass == 0;
                        try (Stream<Path> st2 = Files.list(d)) {
                            for (Path jp : (Iterable<Path>) st2.filter(x -> x.toString().endsWith(".jar")).sorted()::iterator) {
                                String[] m = readMeta(jp); if (!q.isEmpty() && !(m[1] + jp.getFileName()).toLowerCase().contains(q)) continue;
                                list.add(row(m[1] + (m[2].isEmpty() ? "" : "  " + m[2]) + (on ? "" : "  (disabled)"), cut(m[3].isEmpty() ? jp.getFileName().toString() : m[3].replace('\n', ' ')),
                                    on ? "Disable" : "Enable", () -> { moveMod(jp, on, m[0]); reload[0].run(); },
                                    "Remove", () -> { try { Files.deleteIfExists(jp); } catch (Exception ex) { mlog.accept("Could not remove: " + ex.getMessage()); } reload[0].run(); }));
                            }
                        } catch (Exception ex) { mlog.accept("Could not list mods: " + ex.getMessage()); } }
                    mst.setText(list.getComponentCount() + " mods for Minecraft " + MC); list.revalidate(); list.repaint();
                } else {
                    String q = msearch.getText().trim(); boolean cfs = browse[1]; mst.setText("Searching...");
                    new Thread(() -> { try { List<String[]> hits = cfs ? searchCurse(q) : searchModrinth(q);
                        SwingUtilities.invokeLater(() -> { list.removeAll();
                            for (String[] h : hits) list.add(row(h[0], cut(h[1]), "Install", () -> new Thread(() -> { try { Path md = inst().resolve("mods"); Files.createDirectories(md);
                                    Set<String> seen = new HashSet<>(); List<String> got = new ArrayList<>(); mlog.accept("Installing " + h[0] + "...");
                                    if (cfs) installCurse(h[2], md, seen, got, mlog); else installMod(h[2], md, seen, got, mlog);
                                    dedupeAll(md, mlog); mlog.accept("Finished: " + h[0]); } catch (Exception ex) { mlog.accept("Install failed: " + ex.getMessage()); } }).start(), null, null));
                            mst.setText(hits.size() + " results"); list.revalidate(); list.repaint(); });
                    } catch (Exception ex) { SwingUtilities.invokeLater(() -> mst.setText(cut("Search failed: " + ex.getMessage()))); } }).start();
                }
            };
            tInst.addActionListener(e -> { browse[0] = false; tInst.sel = true; tBrow.sel = false; mgo.setText("Filter"); reload[0].run(); });
            tBrow.addActionListener(e -> { browse[0] = true; tInst.sel = false; tBrow.sel = true; mgo.setText("Search"); reload[0].run(); });
            msrc.addActionListener(e -> { browse[1] = !browse[1]; msrc.setText(browse[1] ? "CurseForge" : "Modrinth"); if (browse[0]) reload[0].run(); });
            mgo.addActionListener(e -> reload[0].run()); msearch.addActionListener(e -> reload[0].run());
            opn.addActionListener(e -> { try { Files.createDirectories(inst().resolve("mods")); Desktop.getDesktop().open(inst().resolve("mods").toFile()); } catch (Exception ex) { mlog.accept("Could not open folder: " + ex.getMessage()); } });
            imp.addActionListener(e -> { JFileChooser fc = new JFileChooser(); fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Mods and packs (.jar, .mrpack)", "jar", "mrpack"));
                if (fc.showOpenDialog(f) != JFileChooser.APPROVE_OPTION) return; Path src = fc.getSelectedFile().toPath();
                new Thread(() -> { try { Path md = inst().resolve("mods"); Files.createDirectories(md);
                    if (src.toString().endsWith(".mrpack")) importMrpack(src, mlog); else { Files.copy(src, md.resolve(src.getFileName()), StandardCopyOption.REPLACE_EXISTING); dedupeAll(md, mlog); }
                    mlog.accept("Imported " + src.getFileName()); SwingUtilities.invokeLater(() -> { if (!browse[0]) reload[0].run(); });
                } catch (Exception ex) { mlog.accept("Import failed: " + ex.getMessage()); } }).start(); });

            // ---- Advanced page ----
            JPanel ap = new JPanel(null); ap.setOpaque(false); ap.setBounds(200, 0, 700, 580); ap.setVisible(false); bg.add(ap);
            JLabel adh = label("Advanced", 40, Font.BOLD, TXT); adh.setBounds(48, 40, 500, 54); ap.add(adh);
            Card ad = new Card(); ad.setBounds(48, 110, 604, 420); ap.add(ad);
            ad.add(tag("THEME", 24, 14)); Swatch[] sws = new Swatch[THEMES.length]; int curTheme = 0; try { curTheme = Integer.parseInt(cfg.getProperty("theme", "0")); } catch (Exception ignored) {}
            for (int i = 0; i < THEMES.length; i++) { int k = i; Swatch sw2 = new Swatch(new Color(Integer.parseInt(THEMES[k][0], 16)), new Color(Integer.parseInt(THEMES[k][1], 16)));
                sw2.sel = k == curTheme; sw2.setBounds(24 + k * 44, 36, 36, 36); sws[k] = sw2; ad.add(sw2);
                sw2.addMouseListener(new java.awt.event.MouseAdapter() { @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    applyTheme(k); cfg.setProperty("theme", "" + k); save(); for (int j = 0; j < sws.length; j++) { sws[j].sel = (j == k); sws[j].repaint(); } bg.cache = null; f.repaint(); } }); }
            Btn fxB = new Btn("Animations: " + (FX ? "ON" : "OFF"), false), minB = new Btn("Minimize on launch: " + ("true".equals(cfg.getProperty("min")) ? "ON" : "OFF"), false);
            fxB.setFont(font(Font.PLAIN, 13)); minB.setFont(font(Font.PLAIN, 13)); fxB.setBounds(24, 92, 180, 34); minB.setBounds(212, 92, 240, 34); ad.add(fxB); ad.add(minB);
            fxB.addActionListener(e -> { FX = !FX; cfg.setProperty("fx", "" + FX); save(); fxB.setText("Animations: " + (FX ? "ON" : "OFF")); });
            minB.addActionListener(e -> { boolean on = !"true".equals(cfg.getProperty("min")); cfg.setProperty("min", "" + on); save(); minB.setText("Minimize on launch: " + (on ? "ON" : "OFF")); });
            ad.add(tag("JVM ARGUMENTS", 24, 140)); JTextField jv = field(""); jv.setBounds(24, 160, 556, 38); ad.add(jv); bind(jv, "jvm");
            String[][] fl = {{"WIDTH", "w"}, {"HEIGHT", "h"}, {"RENDER DISTANCE", "rd"}, {"MAX FPS", "fps"}};
            for (int i = 0; i < 4; i++) { int x = i < 2 ? 24 + i * 128 : 300 + (i - 2) * 128; ad.add(tag(fl[i][0], x, 206)); JTextField tf = field(""); tf.setBounds(x, 226, 120, 38); ad.add(tf); bind(tf, fl[i][1]); }
            ad.add(tag("CURSEFORGE API KEY", 24, 274)); JTextField ck = field(""); ck.setBounds(24, 294, 420, 38); ad.add(ck);
            Btn sk = new Btn("Save key", false); sk.setFont(font(Font.PLAIN, 13)); sk.setBounds(456, 294, 124, 38); ad.add(sk);
            JLabel kh = label(cfKey().isEmpty() ? "Free key from console.curseforge.com. Stored encrypted on this PC." : "A key is saved. Paste a new one to replace it.", 12, Font.PLAIN, DIM); kh.setBounds(24, 340, 556, 18); ad.add(kh);
            sk.addActionListener(e -> { try { cfg.setProperty("cfkey", ck.getText().trim().isEmpty() ? "" : protect(ck.getText().trim())); save(); ck.setText(""); kh.setText("Key saved."); } catch (Exception ex) { kh.setText("Could not save key: " + cut(ex.getMessage())); } });
            JPanel[] pgs = {play, set, acc, mp, ap}; Btn[] nvs = {navPlay, navSet, navAcc, navMods, navAdv};
            for (int i = 0; i < 5; i++) { int k = i; nvs[k].addActionListener(e -> { for (int j = 0; j < 5; j++) { pgs[j].setVisible(j == k); nvs[j].sel = (j == k); } if (pgs[k] == mp && !browse[0]) reload[0].run(); bg.repaint(); }); }


            java.util.function.Consumer<String> out = s -> SwingUtilities.invokeLater(() -> { log.append(s + "\n"); log.setCaretPosition(log.getDocument().getLength()); });
            LOG = out; PROG = bar::set;
            vB.addActionListener(e -> { if (VURL.isEmpty()) { out.accept("Version list is still loading..."); return; }
                popup(vB, new ArrayList<>(VURL.keySet()), MC, nv -> { MC = nv; cfg.setProperty("version", nv); save(); t.setText("Minecraft " + nv); vB.setText("Version  " + nv + " \u25be"); ver.setText("v0.2  \u00b7  Minecraft " + nv); }); });
            pB.addActionListener(e -> popup(pB, List.of("Potato", "Low", "Balanced", "High FPS", "Quality"), profile, np -> { profile = np; cfg.setProperty("profile", np); save(); pB.setText("Profile  " + np + " \u25be"); }));
            new Thread(() -> { try { loadVersions(); out.accept("Loaded " + VURL.size() + " Minecraft versions."); } catch (Exception ex2) { out.accept("Could not load version list: " + ex2); } }).start();
            Runnable lock = () -> SwingUtilities.invokeLater(() -> { playB.setEnabled(false); instB.setEnabled(false); });
            Runnable unlock = () -> SwingUtilities.invokeLater(() -> { playB.setEnabled(true); instB.setEnabled(true); });

            instB.addActionListener(e -> new Thread(() -> { lock.run();
                try { install(out); } catch (Exception ex) { out.accept("ERROR: " + ex); bar.set(0, "Install failed"); }
                finally { unlock.run(); } }).start());
            playB.addActionListener(e -> new Thread(() -> { lock.run();
                try { String n = name.getText().trim().isEmpty() ? "Player" : name.getText().trim(); cfg.setProperty("name", n); save();
                    Process p = launch(n, ram.v); bar.set(1, "Game running"); if ("true".equals(cfg.getProperty("min"))) f.setState(Frame.ICONIFIED);
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) { String s; while ((s = r.readLine()) != null) out.accept(s); }
                    int code = p.waitFor(); f.setState(Frame.NORMAL); out.accept("Game exited with code " + code); bar.set(0, code == 0 ? "Ready" : "Game crashed - see log"); }
                catch (Exception ex) { out.accept("ERROR: " + ex); bar.set(0, "Launch failed"); }
                finally { unlock.run(); } }).start());

            f.pack(); f.setResizable(false); f.setLocationRelativeTo(null);
            try { f.setShape(new RoundRectangle2D.Double(0, 0, 900, 580, 26, 26)); f.setOpacity(0f); } catch (Exception ignored) {}
            f.setVisible(true);
            new javax.swing.Timer(16, e -> { float o = f.getOpacity() + 0.08f; if (o >= 1f) { o = 1f; ((javax.swing.Timer) e.getSource()).stop(); }
                try { f.setOpacity(o); } catch (Exception ignored) {} }).start();
        });
    }
}
