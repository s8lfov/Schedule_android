package com.iv234.schedule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Строгая проверка данных с сервера. Из ответа берутся только известные поля
 * нужной формы, а итоговый JSON собирается заново. Любое отклонение — данные отбрасываются.
 */
final class StateValidator {
    private StateValidator() {}

    static String sanitize(String raw) {
        try {
            JSONObject in = new JSONObject(raw);
            int rev = in.optInt("rev", 0);
            if (rev < 0) return null;
            JSONObject out = new JSONObject();
            out.put("rev", rev);
            if (!in.has("bells")) return out.toString(); // пустое состояние: действует базовое расписание

            JSONObject bells = in.getJSONObject("bells");
            JSONObject ob = new JSONObject();
            for (String k : new String[]{"mon", "vsp", "thu"}) {
                JSONObject g = bells.getJSONObject(k);
                JSONArray rows = g.getJSONArray("rows");
                if (rows.length() > 20) return null;
                JSONArray orows = new JSONArray();
                for (int i = 0; i < rows.length(); i++) {
                    JSONArray r = rows.getJSONArray(i);
                    if (r.length() < 3 || r.length() > 4) return null;
                    JSONArray o = new JSONArray();
                    for (int j = 0; j < 3; j++) o.put(str(r.get(j), 60));
                    if (r.length() == 4 && r.optInt(3, 0) != 0) o.put(1);
                    orows.put(o);
                }
                ob.put(k, new JSONObject().put("name", str(g.opt("name") == null ? "" : g.get("name"), 60)).put("rows", orows));
            }
            out.put("bells", ob);

            JSONObject oov = new JSONObject();
            JSONObject ov = in.optJSONObject("ov");
            if (ov != null) {
                if (ov.length() > 400) return null;
                for (Iterator<String> it = ov.keys(); it.hasNext(); ) {
                    String d = it.next();
                    if (!d.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
                    JSONObject day = ov.getJSONObject(d), od = new JSONObject();
                    for (int p = 1; p <= 5; p++) {
                        JSONArray arr = day.optJSONArray(String.valueOf(p));
                        JSONArray oa = new JSONArray();
                        if (arr != null) {
                            if (arr.length() > 8) return null;
                            for (int i = 0; i < arr.length(); i++) {
                                JSONArray x = arr.getJSONArray(i);
                                if (x.length() < 3) return null;
                                oa.put(new JSONArray().put(str(x.get(0), 120)).put(str(x.get(1), 120)).put(str(x.get(2), 120)));
                            }
                        }
                        od.put(String.valueOf(p), oa);
                    }
                    oov.put(d, od);
                }
            }
            out.put("ov", oov);

            JSONObject osh = new JSONObject();
            JSONObject sh = in.optJSONObject("sh");
            if (sh != null) {
                if (sh.length() > 300) return null;
                for (Iterator<String> it = sh.keys(); it.hasNext(); ) {
                    String k = it.next();
                    str(k, 260);
                    if (sh.optInt(k, 0) != 0 || sh.optBoolean(k, false)) osh.put(k, 1);
                }
            }
            out.put("sh", osh);
            return out.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Только строка, не длиннее max, без < > и управляющих символов. */
    private static String str(Object o, int max) throws Exception {
        if (!(o instanceof String)) throw new IllegalArgumentException();
        String v = (String) o;
        if (v.length() > max) throw new IllegalArgumentException();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '<' || c == '>' || c < 32) throw new IllegalArgumentException();
        }
        return v;
    }
}
