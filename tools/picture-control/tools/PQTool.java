// PQTool - generic Sony/MediaTek picture-config get/set/range tool (x90h-internal, 2026-11-17).
// Runs via app_process in the sony_platform_app context with CLASSPATH including
// com.mediatek.twoworlds.tv.jar. Uses reflection only, so it needs no compile-time deps.
//   get <key> [inputGroup]        -> current value (inputGroup default -1 = current input)
//   default <key>                 -> factory default value
//   minmax <key>                  -> min max (decoded via the class's own static helpers)
//   set <key> <val> [inputGroup]  -> persistent set (isUpdate=3, same as PQSetOnce)
// Read verbs (get/default/minmax) make NO changes. Only 'set' writes.
import java.lang.reflect.Method;

public class PQTool {
  public static void main(String[] a) {
    try {
      if (a.length < 2) { System.out.println("ERR usage: get|default|minmax|set <key> [..]"); return; }
      String cmd = a[0], key = a[1];
      Class<?> c = Class.forName("com.mediatek.twoworlds.tv.MtkTvConfig");
      Object cfg = c.getMethod("getInstance").invoke(null);
      if (cmd.equals("dump")) {
        // dump <key1> <key2> ... : one line per key, read-only (minmax+current+default)
        Method gv = c.getMethod("getConfigValue", int.class, String.class);
        Method gd = c.getMethod("getConfigDefaultValue", String.class);
        Method gm = c.getMethod("getMinMaxConfigValue", String.class);
        Method mn = c.getMethod("getMinValue", int.class);
        Method mx = c.getMethod("getMaxValue", int.class);
        for (int i = 1; i < a.length; i++) {
          String k = a[i];
          try {
            int packed = (Integer) gm.invoke(cfg, k);
            int lo = (Integer) mn.invoke(null, packed), hi = (Integer) mx.invoke(null, packed);
            int cur = (Integer) gv.invoke(cfg, -1, k);
            int def = (Integer) gd.invoke(cfg, k);
            System.out.println("DUMP " + k + " min=" + lo + " max=" + hi + " cur=" + cur + " def=" + def + " packed=" + packed);
          } catch (Throwable e) {
            System.out.println("DUMP " + k + " ERR " + e);
          }
        }
        return;
      }
      if (cmd.equals("setmany")) {
        // setmany <key1> <val1> <key2> <val2> ... : persistent set of many keys in one JVM.
        Method sv = c.getMethod("setConfigValue", String.class, int.class);
        int ok = 0, bad = 0;
        for (int i = 1; i + 1 < a.length; i += 2) {
          try {
            int rc = (Integer) sv.invoke(cfg, a[i], Integer.parseInt(a[i + 1]));
            if (rc >= 0) ok++; else { bad++; System.out.println("SETFAIL " + a[i] + " rc=" + rc); }
          } catch (Throwable e) { bad++; System.out.println("SETFAIL " + a[i] + " " + e); }
        }
        System.out.println("SETMANY ok=" + ok + " bad=" + bad);
        return;
      }
      if (cmd.equals("get")) {
        int g = a.length >= 3 ? Integer.parseInt(a[2]) : -1;
        int v = (Integer) c.getMethod("getConfigValue", int.class, String.class).invoke(cfg, g, key);
        System.out.println("GET " + key + " g=" + g + " value=" + v);
      } else if (cmd.equals("default")) {
        int v = (Integer) c.getMethod("getConfigDefaultValue", String.class).invoke(cfg, key);
        System.out.println("DEFAULT " + key + " value=" + v);
      } else if (cmd.equals("minmax")) {
        int packed = (Integer) c.getMethod("getMinMaxConfigValue", String.class).invoke(cfg, key);
        int mn = (Integer) c.getMethod("getMinValue", int.class).invoke(null, packed);
        int mx = (Integer) c.getMethod("getMaxValue", int.class).invoke(null, packed);
        System.out.println("MINMAX " + key + " min=" + mn + " max=" + mx + " packed=" + packed);
      } else if (cmd.equals("set")) {
        if (a.length < 3) { System.out.println("ERR set needs <val>"); return; }
        int val = Integer.parseInt(a[2]);
        int rc;
        if (a.length >= 4) {
          int g = Integer.parseInt(a[3]);
          rc = (Integer) c.getMethod("setConfigValue", int.class, String.class, int.class, int.class)
                          .invoke(cfg, g, key, val, 3);
          System.out.println("SET " + key + " g=" + g + " value=" + val + " rc=" + rc);
        } else {
          rc = (Integer) c.getMethod("setConfigValue", String.class, int.class).invoke(cfg, key, val);
          System.out.println("SET " + key + " value=" + val + " rc=" + rc);
        }
      } else {
        System.out.println("ERR unknown cmd " + cmd);
      }
    } catch (Throwable t) {
      System.out.println("ERR " + t);
    }
  }
}
