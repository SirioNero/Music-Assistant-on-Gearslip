package android.util

/** Stub di android.util.Log per provare il codice sulla JVM. */
object Log {
    @JvmStatic fun d(tag: String, msg: String): Int { println("D/$tag: $msg"); return 0 }
    @JvmStatic fun i(tag: String, msg: String): Int { println("I/$tag: $msg"); return 0 }
    @JvmStatic fun w(tag: String, msg: String): Int { println("W/$tag: $msg"); return 0 }
    @JvmStatic fun w(tag: String, msg: String, t: Throwable): Int { println("W/$tag: $msg: $t"); return 0 }
    @JvmStatic fun e(tag: String, msg: String): Int { println("E/$tag: $msg"); return 0 }
    @JvmStatic fun e(tag: String, msg: String, t: Throwable): Int { println("E/$tag: $msg: $t"); t.printStackTrace(); return 0 }
}
