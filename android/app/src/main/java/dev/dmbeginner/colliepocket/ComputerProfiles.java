package dev.dmbeginner.colliepocket;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;
import java.util.UUID;

/** Connection names and addresses only. Pairing credentials remain in origin-scoped WebView storage. */
public final class ComputerProfiles {
    public static final class Computer {
        public final String id, name, address;
        Computer(String id, String name, String address) {
            this.id = id; this.name = name; this.address = address;
        }
    }
    private final SharedPreferences preferences;
    private final ArrayList<Computer> computers = new ArrayList<>();
    private String activeId;
    private final Set<String> seeded;

    public ComputerProfiles(SharedPreferences preferences, String defaultAddress) {
        this(preferences, defaultAddress, "[]");
    }
    public ComputerProfiles(SharedPreferences preferences, String defaultAddress, String defaultComputers) {
        this.preferences = preferences;
        boolean migrating = !preferences.contains("computers");
        seeded = new LinkedHashSet<>(preferences.getStringSet("seeded_computers", Collections.emptySet()));
        if (preferences.contains("computers")) {
            try {
                JSONArray saved = new JSONArray(preferences.getString("computers", "[]"));
                for (int i = 0; i < saved.length(); i++) {
                    try {
                        JSONObject row = saved.getJSONObject(i);
                        String id = row.getString("id"), name = validName(row.getString("name"));
                        String address = ServerAddress.normalize(row.getString("address"));
                        if (!id.isEmpty() && find(id) == null && findAddress(address, null) == null)
                            computers.add(new Computer(id, name, address));
                    } catch (JSONException | IllegalArgumentException ignored) { /* Skip a damaged row. */ }
                }
            } catch (JSONException ignored) { /* Allow recovery through Add computer. */ }
        } else {
            String previous = preferences.getString("server", defaultAddress);
            try {
                if (previous != null && !previous.isEmpty())
                    computers.add(new Computer(UUID.randomUUID().toString(), "我的电脑", ServerAddress.normalize(previous)));
            } catch (IllegalArgumentException ignored) { /* Invalid old address needs user correction. */ }
        }
        activeId = preferences.getString("active_computer", "");
        try {
            JSONArray defaults = new JSONArray(defaultComputers);
            for (int i = 0; i < defaults.length(); i++) {
                try {
                    JSONObject row = defaults.getJSONObject(i);
                    String name = validName(row.getString("name"));
                    String address = ServerAddress.normalize(row.getString("address"));
                    if (seeded.contains(address)) continue;
                    Computer existing = findAddress(address, null);
                    if (existing == null) computers.add(new Computer(UUID.randomUUID().toString(), name, address));
                    else if (migrating) computers.set(computers.indexOf(existing), new Computer(existing.id, name, address));
                    seeded.add(address);
                } catch (JSONException | IllegalArgumentException ignored) { /* Ignore an invalid build default. */ }
            }
        } catch (JSONException ignored) { /* The computer list remains editable if defaults are damaged. */ }
        if (find(activeId) == null) activeId = computers.isEmpty() ? "" : computers.get(0).id;
        save();
    }
    public List<Computer> list() { return new ArrayList<>(computers); }
    public Computer active() { return find(activeId); }
    private Computer find(String id) {
        for (Computer computer : computers) if (computer.id.equals(id)) return computer;
        return null;
    }
    private Computer findAddress(String address, String exceptId) {
        for (Computer computer : computers)
            if (!computer.id.equals(exceptId) && ServerAddress.sameOrigin(computer.address, address)) return computer;
        return null;
    }
    private String validName(String value) {
        String name = value.trim();
        if (name.isEmpty() || name.length() > 64) throw new IllegalArgumentException("电脑名称需要 1 到 64 个字符");
        return name;
    }
    public Computer put(String id, String name, String address) {
        name = validName(name);
        address = ServerAddress.normalize(address);
        if (findAddress(address, id) != null) throw new IllegalArgumentException("这个地址已经添加，请从电脑列表中选择");
        Computer previous = id == null ? null : find(id);
        if (id != null && previous == null) throw new IllegalArgumentException("电脑配置已移除，请重新添加");
        Computer changed = new Computer(id == null ? UUID.randomUUID().toString() : id, name, address);
        if (previous == null) computers.add(changed);
        else computers.set(computers.indexOf(previous), changed);
        activeId = changed.id;
        save();
        return changed;
    }
    public void select(String id) {
        if (find(id) == null) throw new IllegalArgumentException("电脑配置不存在");
        activeId = id; save();
    }
    public void remove(String id) {
        Computer previous = find(id);
        if (previous == null) return;
        computers.remove(previous);
        if (id.equals(activeId)) activeId = computers.isEmpty() ? "" : computers.get(0).id;
        save();
    }
    private void save() {
        JSONArray saved = new JSONArray();
        for (Computer computer : computers) {
            JSONObject row = new JSONObject();
            try { row.put("id", computer.id).put("name", computer.name).put("address", computer.address); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            saved.put(row);
        }
        // Keep the old key for in-place upgrades; it never contains credentials.
        preferences.edit().putString("computers", saved.toString()).putString("active_computer", activeId)
                .putStringSet("seeded_computers", seeded)
                .putString("server", active() == null ? "" : active().address).apply();
    }
}
