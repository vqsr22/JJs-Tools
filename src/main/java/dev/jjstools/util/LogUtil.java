package dev.jjstools.util;

import dev.jjstools.JJsTools;

public class LogUtil {
    public static void info(String msg) {
        JJsTools.LOG.info("{} {}", MsgUtil.getRawPrefix(), msg);
    }
    public static void info(String msg, String module) {
        JJsTools.LOG.info("{}{} {}", MsgUtil.getRawPrefix(), MsgUtil.getRawPrefix(module), msg);
    }
    public static void warn(String msg) {
        JJsTools.LOG.warn("{} {}", MsgUtil.getRawPrefix(), msg);
    }
    public static void warn(String msg, String module) {
        JJsTools.LOG.warn("{}{} {}", MsgUtil.getRawPrefix(), MsgUtil.getRawPrefix(module), msg);
    }
    public static void error(String msg) {
        JJsTools.LOG.error("{} {}", MsgUtil.getRawPrefix(), msg);
    }
    public static void error(String msg, String module) {
        JJsTools.LOG.error("{}{} {}", MsgUtil.getRawPrefix(), MsgUtil.getRawPrefix(module), msg);
    }
    public static void debug(String msg) {
        JJsTools.LOG.debug("{} {}", MsgUtil.getRawPrefix(), msg);
    }
    public static void debug(String msg, String module) {
        JJsTools.LOG.debug("{}{} {}", MsgUtil.getRawPrefix(), MsgUtil.getRawPrefix(module), msg);
    }
}
