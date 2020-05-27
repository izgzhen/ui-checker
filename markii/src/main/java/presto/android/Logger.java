/*
 * Logger.java - part of the GATOR project
 *
 * Copyright (c) 2018 The Ohio State University
 *
 * This file is distributed under the terms described in LICENSE in the
 * root directory.
 */

package presto.android;

public class Logger {
  private static boolean enableTrace = false;
  private static final String TAG = Logger.class.getSimpleName();

  public static void setTracing(boolean verbose) {
    enableTrace = verbose;
  }

  public static void verb(String tag, String msg) {
    System.out.println("[" + tag + "] " + ANSI.GREEN + "VERBOSE" + ANSI.RESET + " " + msg);
  }

  public static void trace(String tag, String msg) {
    if (enableTrace) {
      System.out.println("[" + tag + "] " + ANSI.CYAN + "TRACE" + ANSI.RESET + " " + msg);
    }
  }

  public static void warn(String tag, String msg) {
    System.out.println("[" + tag + "] " + ANSI.RED + "WARN" + ANSI.RESET + " " + msg);
  }

  public static void err(String tag, String msg) {
    System.err.println("[" + tag + "] " + ANSI.RED_BACKGROUND + "ERROR " + msg + ANSI.RESET);
  }

  public static void stat(String msg) {
    System.out.println(ANSI.WHITE_BACKGROUND + ANSI.BLACK + "[STAT]" + " " + msg + ANSI.RESET);
  }

  private class ANSI {
    private static final String RESET = "\u001B[0m";
    private static final String BLACK = "\u001B[30m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String PURPLE = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";
    private static final String WHITE = "\u001B[37m";

    private static final String BLACK_BACKGROUND = "\u001B[40m";
    private static final String RED_BACKGROUND = "\u001B[41m";
    private static final String GREEN_BACKGROUND = "\u001B[42m";
    private static final String YELLOW_BACKGROUND = "\u001B[43m";
    private static final String BLUE_BACKGROUND = "\u001B[44m";
    private static final String PURPLE_BACKGROUND = "\u001B[45m";
    private static final String CYAN_BACKGROUND = "\u001B[46m";
    private static final String WHITE_BACKGROUND = "\u001B[47m";
  }
}
