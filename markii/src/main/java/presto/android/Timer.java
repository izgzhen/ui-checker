/*
 * Timer.java - part of the GATOR project
 *
 * Copyright (c) 2018 The Ohio State University
 *
 * This file is distributed under the terms described in LICENSE in the
 * root directory.
 */

package presto.android;

public class Timer {
  private static long start;

  public static void reset() {
    start = System.currentTimeMillis();
  }

  public static long duration() {
    return System.currentTimeMillis() - start;
  }
}
