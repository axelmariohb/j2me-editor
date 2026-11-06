package pl.zb3.freej2me.liveedit;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Live value scanner/editor for the running midlet.
 * Commands (from JS via Shell.liveEditPoll):
 *   SCAN <number>          -> lines "path<TAB>type<TAB>value"
 *   SET <path> <number>    -> "OK ..." or "ERR ..."
 *   INFO                   -> loader/jar info
 * Path format: pkg.Class#field#field[3]#...
 */
public class LiveEdit {
    private static ClassLoader loader;
    private static String jarPath;
    private static List<String> classNames;
    private static Object midletRoot;

    private static void ensureRoot() {
        if (midletRoot != null || loader == null) return;
        try {
            java.lang.reflect.Field f = loader.getClass().getDeclaredField("mainInst");
            f.setAccessible(true);
            midletRoot = f.get(loader);
        } catch (Throwable t) { }
    }

    public static void setLoader(ClassLoader cl, String jar) {
        loader = cl;
        jarPath = jar;
    }

    public static synchronized String handle(String cmd) {
        try {
            if (cmd == null) return "ERR empty";
            cmd = cmd.trim();
            if (cmd.equals("INFO")) return info();
            if (cmd.startsWith("SCAN ")) return scan(cmd.substring(5).trim());
            if (cmd.startsWith("SET ")) {
                String rest = cmd.substring(4).trim();
                int sp = rest.indexOf(' ');
                if (sp < 0) return "ERR usage: SET path value";
                return set(rest.substring(0, sp), rest.substring(sp + 1).trim());
            }
            return "ERR unknown";
        } catch (Throwable t) {
            return "ERR " + t.getClass().getName() + ": " + t.getMessage();
        }
    }

    private static String info() {
        try {
            ensureClasses();
            return "OK classes=" + classNames.size() + " jar=" + jarPath;
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    private static void ensureClasses() throws Exception {
        if (classNames != null) return;
        if (loader == null || jarPath == null) throw new Exception("no loader registered");
        classNames = new ArrayList<String>();
        ZipFile zf = new ZipFile(jarPath);
        try {
            List<? extends ZipEntry> entries = Collections.list(zf.entries());
            for (ZipEntry e : entries) {
                String n = e.getName();
                if (n.endsWith(".class") && !n.contains("$")) {
                    classNames.add(n.substring(0, n.length() - 6).replace('/', '.'));
                }
            }
        } finally {
            zf.close();
        }
    }

    private static boolean isNumericType(Class<?> t) {
        return t == int.class || t == long.class || t == short.class || t == byte.class
            || t == float.class || t == double.class || t == char.class;
    }

    private static double asDouble(Object v) {
        if (v instanceof Character) return (double) ((Character) v).charValue();
        return ((Number) v).doubleValue();
    }

    private static boolean matches(Object v, double target, boolean isFloatKind) {
        if (v == null) return false;
        double d = asDouble(v);
        if (isFloatKind) {
            // compare via float/double parsing of the query as well
            return d == target || String.valueOf(v).equals(String.valueOf(target))
                || ("" + v).equals(trimNum(target));
        }
        return d == target;
    }

    private static String trimNum(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    private static String scan(String query) {
        try {
            ensureClasses();
        } catch (Throwable t) {
            return "ERR " + t;
        }
        double target;
        try {
            target = Double.parseDouble(query.replace(",", "."));
        } catch (Exception e) {
            return "ERR not a number: " + query;
        }
        StringBuilder out = new StringBuilder();
        int[] found = {0};
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        int[] nodes = {0};
        ensureRoot();
        if (midletRoot != null) {
            walk(midletRoot, "MIDlet", target, out, found, seen, nodes, 0);
        }
        for (String cn : classNames) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, loader);
            } catch (Throwable t) {
                continue;
            }
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fields) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (f.isSynthetic()) continue;
                try {
                    f.setAccessible(true);
                } catch (Throwable t) {
                    continue;
                }
                String path = cn + "#" + f.getName();
                Object val;
                try {
                    val = f.get(null);
                } catch (Throwable t) {
                    continue;
                }
                if (isNumericType(f.getType())) {
                    if (matches(val, target, f.getType() == float.class || f.getType() == double.class)) {
                        out.append(path).append('\t').append(f.getType().getName()).append('\t').append(val).append('\n');
                        if (++found[0] > 200) return out.append("...truncated\n").toString();
                    }
                } else if (val != null) {
                    walk(val, path, target, out, found, seen, nodes, 0);
                    if (found[0] > 200) return out.append("...truncated\n").toString();
                }
            }
        }
        if (found[0] == 0) return "OK 0 matches";
        return "OK " + found[0] + " matches\n" + out.toString();
    }

    private static void walk(Object obj, String path, double target, StringBuilder out,
                             int[] found, IdentityHashMap<Object, Boolean> seen, int[] nodes, int depth) {
        if (obj == null || depth > 10 || found[0] > 200 || nodes[0] > 100000) return;
        Class<?> c = obj.getClass();
        String cn = c.getName();
        if (obj instanceof java.util.Vector) {
            if (seen.containsKey(obj)) return;
            seen.put(obj, Boolean.TRUE);
            java.util.Vector v = (java.util.Vector) obj;
            int n = v.size();
            for (int i = 0; i < n; i++) {
                walk(v.elementAt(i), path + "{" + i + "}", target, out, found, seen, nodes, depth + 1);
                if (found[0] > 200) return;
            }
            return;
        }
        if (obj instanceof java.util.Hashtable) {
            if (seen.containsKey(obj)) return;
            seen.put(obj, Boolean.TRUE);
            java.util.Hashtable h = (java.util.Hashtable) obj;
            for (Object k : h.keySet()) {
                walk(h.get(k), path + "{" + k + "}", target, out, found, seen, nodes, depth + 1);
                if (found[0] > 200) return;
            }
            return;
        }
        if (c.isPrimitive() || cn.startsWith("java.") || cn.startsWith("javax.")
            || cn.startsWith("pl.zb3.") || cn.startsWith("org.recompile.")) return;
        if (seen.containsKey(obj)) return;
        seen.put(obj, Boolean.TRUE);
        nodes[0]++;
        if (c.isArray()) {
            Class<?> ct = c.getComponentType();
            int len = Array.getLength(obj);
            if (len > 100000) return;
            for (int i = 0; i < len; i++) {
                Object el = Array.get(obj, i);
                String p = path + "[" + i + "]";
                if (ct.isPrimitive() && ct != boolean.class) {
                    if (matches(el, target, ct == float.class || ct == double.class)) {
                        out.append(p).append('\t').append(ct.getName()).append('\t').append(el).append('\n');
                        found[0]++;
                    }
                } else if (el != null) {
                    walk(el, p, target, out, found, seen, nodes, depth + 1);
                }
                if (found[0] > 200) return;
            }
            return;
        }
        while (c != null && !c.getName().startsWith("java.")) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                try {
                    f.setAccessible(true);
                } catch (Throwable t) {
                    continue;
                }
                Object val;
                try {
                    val = f.get(obj);
                } catch (Throwable t) {
                    continue;
                }
                String p = path + "#" + f.getName();
                if (isNumericType(f.getType())) {
                    if (matches(val, target, f.getType() == float.class || f.getType() == double.class)) {
                        out.append(p).append('\t').append(f.getType().getName()).append('\t').append(val).append('\n');
                        if (++found[0] > 200) return;
                    }
                } else if (val != null && !(val instanceof String)) {
                    walk(val, p, target, out, found, seen, nodes, depth + 1);
                    if (found[0] > 200) return;
                }
            }
            c = c.getSuperclass();
        }
    }

    private static String set(String path, String value) {
        try {
            ensureClasses();
            // parse path: first segment is class name up to first '#'
            int hash = path.indexOf('#');
            if (hash < 0) return "ERR bad path";
            String cn = path.substring(0, hash);
            ensureRoot();
            Class<?> c;
            Object rootObj = null;
            if (cn.equals("MIDlet") && midletRoot != null) {
                c = midletRoot.getClass();
                rootObj = midletRoot;
            } else {
                c = Class.forName(cn, false, loader);
            }
            List<String> segs = new ArrayList<String>();
            String rest = path.substring(hash);
            while (rest.startsWith("#")) {
                int next = rest.indexOf('#', 1);
                segs.add(next < 0 ? rest.substring(1) : rest.substring(1, next));
                rest = next < 0 ? "" : rest.substring(next);
            }
            Object target = rootObj;
            Class<?> cur = c;
            Field lastField = null;
            Object lastObj = null;
            int lastIndex = -1;
            for (int i = 0; i < segs.size(); i++) {
                String seg = segs.get(i);
                int arrIdx = -1;
                String fieldName = seg;
                int b = seg.indexOf('[');
                if (b >= 0) {
                    fieldName = seg.substring(0, b);
                    arrIdx = Integer.parseInt(seg.substring(b + 1, seg.indexOf(']')));
                }
                Field f = findField(cur, fieldName);
                if (f == null) return "ERR no field " + fieldName;
                f.setAccessible(true);
                Object container = Modifier.isStatic(f.getModifiers()) ? null : target;
                if (i == segs.size() - 1 && arrIdx < 0) {
                    lastField = f; lastObj = container;
                    break;
                }
                Object v = f.get(container);
                if (arrIdx >= 0) {
                    if (i == segs.size() - 1) {
                        lastField = null; lastObj = v; lastIndex = arrIdx;
                        break;
                    }
                    v = Array.get(v, arrIdx);
                }
                target = v;
                if (target == null) return "ERR null at " + seg;
                cur = target.getClass();
            }
            value = value.replace(",", ".");
            if (lastField != null) {
                setFieldValue(lastField, lastObj, value);
                return "OK " + path + " = " + lastField.get(lastObj);
            } else if (lastObj != null && lastIndex >= 0) {
                Class<?> ct = lastObj.getClass().getComponentType();
                setArrayValue(lastObj, ct, lastIndex, value);
                return "OK " + path + " = " + Array.get(lastObj, lastIndex);
            }
            return "ERR unresolved";
        } catch (Throwable t) {
            return "ERR " + t.getClass().getName() + ": " + t.getMessage();
        }
    }

    private static Field findField(Class<?> c, String name) {
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (Throwable t) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static void setFieldValue(Field f, Object obj, String value) throws Exception {
        Class<?> t = f.getType();
        if (t == int.class) f.setInt(obj, (int) Double.parseDouble(value));
        else if (t == long.class) f.setLong(obj, (long) Double.parseDouble(value));
        else if (t == short.class) f.setShort(obj, (short) Double.parseDouble(value));
        else if (t == byte.class) f.setByte(obj, (byte) Double.parseDouble(value));
        else if (t == char.class) f.setChar(obj, (char) Double.parseDouble(value));
        else if (t == float.class) f.setFloat(obj, (float) Double.parseDouble(value));
        else if (t == double.class) f.setDouble(obj, Double.parseDouble(value));
        else throw new Exception("not numeric");
    }

    private static void setArrayValue(Object arr, Class<?> ct, int idx, String value) throws Exception {
        if (ct == int.class) Array.setInt(arr, idx, (int) Double.parseDouble(value));
        else if (ct == long.class) Array.setLong(arr, idx, (long) Double.parseDouble(value));
        else if (ct == short.class) Array.setShort(arr, idx, (short) Double.parseDouble(value));
        else if (ct == byte.class) Array.setByte(arr, idx, (byte) Double.parseDouble(value));
        else if (ct == char.class) Array.setChar(arr, idx, (char) Double.parseDouble(value));
        else if (ct == float.class) Array.setFloat(arr, idx, (float) Double.parseDouble(value));
        else if (ct == double.class) Array.setDouble(arr, idx, Double.parseDouble(value));
        else throw new Exception("not numeric");
    }
}
