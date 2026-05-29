/*
 * Copyright (c) 2011 - Georgios Gousios <gousiosg@gmail.com>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package gr.gousiosg.javacg.dyn;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-aware call stack tracker. Each thread gets its own stack so that
 * multi-threaded applications (e.g. WildFly) produce complete per-thread
 * traces rather than only the first thread's calls.
 *
 * calltrace.txt format (one entry per method entry/exit):
 *   >[depth][tid]class:method=nanos   (entry)
 *   <[depth][tid]class:method=nanos   (exit)
 */
public class MethodStack {

    private static final ThreadLocal<Stack<String>> STACKS =
            ThreadLocal.withInitial(Stack::new);

    // global aggregate call-pair → count, written to stdout on shutdown
    private static final ConcurrentMap<Pair<String, String>, Integer> callgraph =
            new ConcurrentHashMap<>();

    static FileWriter fw;

    static {
        File log = new File("/tmp/calltrace.txt");
        try {
            fw = new FileWriter(log);
            System.err.println("[JAVACG-DYN] calltrace.txt opened at: " + log.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[JAVACG-DYN] Failed to open calltrace.txt: " + e);
            e.printStackTrace();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                fw.flush();
                fw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            // print call-pair summary sorted by count ascending
            callgraph.entrySet().stream()
                    .sorted((a, b) -> a.getValue().compareTo(b.getValue()))
                    .forEach(e -> System.out.println(e.getKey() + " " + e.getValue()));
        }));
    }

    public static void push(String callname) throws IOException {
        Stack<String> stack = STACKS.get();
        long tid = Thread.currentThread().getId();

        if (!stack.isEmpty()) {
            Pair<String, String> p = new Pair<>(stack.peek(), callname);
            callgraph.merge(p, 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(">[").append(stack.size()).append(']');
        sb.append('[').append(tid).append(']');
        sb.append(callname).append('=').append(System.nanoTime()).append('\n');
        synchronized (fw) { fw.write(sb.toString()); }

        stack.push(callname);
    }

    public static void pop() throws IOException {
        Stack<String> stack = STACKS.get();
        if (stack.isEmpty()) return;

        long tid = Thread.currentThread().getId();
        String returnFrom = stack.pop();

        StringBuilder sb = new StringBuilder();
        sb.append("<[").append(stack.size()).append(']');
        sb.append('[').append(tid).append(']');
        sb.append(returnFrom).append('=').append(System.nanoTime()).append('\n');
        synchronized (fw) { fw.write(sb.toString()); }
    }
}
