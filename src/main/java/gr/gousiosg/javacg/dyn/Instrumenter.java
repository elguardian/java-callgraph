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
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javassist.CannotCompileException;
import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.LocalVariableAttribute;

public class Instrumenter implements ClassFileTransformer {

    static List<Pattern> pkgIncl = new ArrayList<>();
    static List<Pattern> pkgExcl = new ArrayList<>();

    public static void premain(String argument, Instrumentation instrumentation) {

        // Make agent classes (MethodStack) visible to Javassist's compiler
        ClassPool.getDefault().insertClassPath(new ClassClassPath(MethodStack.class));

        // incl=com.foo.*,gr.bar.foo;excl=com.bar.foo.*

        if (argument == null) {
            err("Missing configuration argument");
            return;
        }

        err("Argument is: " + argument);

        String[] tokens = argument.split(";");

        if (tokens.length < 1) {
            err("Missing delimiter ;");
            return;
        }

        for (String token : tokens) {
            String[] args = token.split("=");
            if (args.length < 2) {
                err("Missing argument delimiter =:" + token);
                return;
            }

            String argtype = args[0];

            if (!argtype.equals("incl") && !argtype.equals("excl")) {
                err("Wrong argument: " + argtype);
                return;
            }

            String[] patterns = args[1].split(",");

            for (String pattern : patterns) {
                Pattern p = null;
                err("Compiling " + argtype + " pattern:" + pattern + "$");
                try {
                    p = Pattern.compile(pattern + "$");
                } catch (PatternSyntaxException pse) {
                    err("pattern: " + pattern + " not valid, ignoring");
                }
                if (argtype.equals("incl"))
                    pkgIncl.add(p);
                else
                    pkgExcl.add(p);
            }
        }

        instrumentation.addTransformer(new Instrumenter());
    }

    public byte[] transform(ClassLoader loader, String className, Class<?> clazz,
            java.security.ProtectionDomain domain, byte[] bytes) {
        boolean enhanceClass = false;

        String name = className.replace("/", ".");

        for (Pattern p : pkgIncl) {
            Matcher m = p.matcher(name);
            if (m.matches()) {
                enhanceClass = true;
                break;
            }
        }

        for (Pattern p : pkgExcl) {
            Matcher m = p.matcher(name);
            if (m.matches()) {
                err("Skipping class: " + name);
                enhanceClass = false;
                break;
            }
        }

        if (enhanceClass) {
            return enhanceClass(loader, className, bytes);
        } else {
            return bytes;
        }
    }

    private byte[] enhanceClass(ClassLoader loader, String name, byte[] b) {
        ClassPool pool = new ClassPool(ClassPool.getDefault());
        if (loader != null) {
            pool.appendClassPath(new javassist.LoaderClassPath(loader));
        }
        CtClass clazz = null;
        try {
            clazz = pool.makeClass(new ByteArrayInputStream(b));
            if (!clazz.isInterface()) {
                err("Enhancing class: " + name);
                CtBehavior[] methods = clazz.getDeclaredBehaviors();
                for (int i = 0; i < methods.length; i++) {
                    if (!methods[i].isEmpty()) {
                        enhanceMethod(methods[i], clazz.getName());
                    }
                }
                b = clazz.toBytecode();
            }
        } catch (CannotCompileException e) {
            e.printStackTrace();
            err("Cannot compile: " + e.getMessage());
        } catch (NotFoundException e) {
            e.printStackTrace();
            err("Cannot find: " + e.getMessage());
        } catch (IOException e) {
            err("Error writing: " + e.getMessage());
        } catch (Throwable t) {
            t.printStackTrace();
            err("Throwable in enhanceClass: " + t);
        } finally {
            if (clazz != null) {
                clazz.detach();
            }
        }
        return b;
    }

    private void enhanceMethod(CtBehavior method, String className)
            throws NotFoundException, CannotCompileException {
        String name = className.substring(className.lastIndexOf('.') + 1, className.length());
        String methodName = method.getName();

        if (method.getName().equals(name))
            methodName = "<init>";

        int line = method.getMethodInfo().getLineNumber(0);
        CtClass[] paramTypes = method.getParameterTypes();
        String[] paramNames = getParamNames(method, paramTypes);

        String methodId = className + ":" + methodName + (line >= 0 ? ":" + line : "");
        String pushArg = buildPushArg(methodId, paramTypes, paramNames);

        method.insertBefore("gr.gousiosg.javacg.dyn.MethodStack.push(" + pushArg + ");");
        method.insertAfter("gr.gousiosg.javacg.dyn.MethodStack.pop();");
    }

    private String buildPushArg(String methodId, CtClass[] paramTypes, String[] paramNames) {
        StringBuilder names = new StringBuilder("new String[]{");
        for (int i = 0; i < paramNames.length; i++) {
            if (i > 0) names.append(',');
            names.append('"').append(paramNames[i]).append('"');
        }
        names.append('}');
        return "\"" + methodId + "\", $args, " + names;
    }

    private String[] getParamNames(CtBehavior method, CtClass[] paramTypes) {
        String[] names = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            names[i] = "arg" + i;
        }
        try {
            javassist.bytecode.CodeAttribute ca = method.getMethodInfo().getCodeAttribute();
            if (ca == null) return names;
            LocalVariableAttribute lva = (LocalVariableAttribute)
                    ca.getAttribute(LocalVariableAttribute.tag);
            if (lva == null) return names;

            boolean isStatic = Modifier.isStatic(method.getModifiers());
            int slot = isStatic ? 0 : 1;
            for (int i = 0; i < paramTypes.length; i++) {
                for (int j = 0; j < lva.tableLength(); j++) {
                    if (lva.index(j) == slot) {
                        names[i] = lva.variableName(j);
                        break;
                    }
                }
                slot += (paramTypes[i] == CtClass.longType || paramTypes[i] == CtClass.doubleType) ? 2 : 1;
            }
        } catch (Exception e) {
            // fallback: keep arg0..argN
        }
        return names;
    }

    private static void err(String msg) {
        System.err.println("[JAVACG-DYN] " + msg);
    }
}