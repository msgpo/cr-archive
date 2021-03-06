/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.incubator.jextract.tool;

import jdk.incubator.foreign.FunctionDescriptor;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * A helper class to generate header interface class in source form.
 * After aggregating various constituents of a .java source, build
 * method is called to get overall generated source string.
 */
class HeaderBuilder extends JavaSourceBuilder {
    HeaderBuilder(String className, String pkgName, ConstantHelper constantHelper) {
        super(className, pkgName, constantHelper);
    }

    public void addFunctionalFactory(String className, MethodType mtype, FunctionDescriptor fDesc) {
        incrAlign();
        indent();
        sb.append(PUB_MODS + "MemoryAddress " + className + "$make(" + className + " fi) {\n");
        incrAlign();
        indent();
        sb.append("return RuntimeHelper.upcallStub(" + className + ".class, fi, " + functionGetCallString(className, fDesc) + ", " +
                "\"" + mtype.toMethodDescriptorString() + "\");\n");
        decrAlign();
        indent();
        sb.append("}\n");
        decrAlign();
    }

    public void addFunctionalInterface(String name, MethodType mtype) {
        incrAlign();
        indent();
        sb.append("public interface " + name + " {\n");
        incrAlign();
        indent();
        sb.append(mtype.returnType().getName() + " apply(");
        String delim = "";
        for (int i = 0 ; i < mtype.parameterCount() ; i++) {
            sb.append(delim + mtype.parameterType(i).getName() + " x" + i);
            delim = ", ";
        }
        sb.append(");\n");
        decrAlign();
        indent();
        sb.append("}\n");
        decrAlign();
        indent();
    }

    public void addStaticFunctionWrapper(String javaName, String nativeName, MethodType mtype, FunctionDescriptor desc, boolean varargs, List<String> paramNames) {
        incrAlign();
        indent();
        sb.append(PUB_MODS + mtype.returnType().getName() + " " + javaName + " (");
        String delim = "";
        List<String> pNames = new ArrayList<>();
        final int numParams = paramNames.size();
        for (int i = 0 ; i < numParams; i++) {
            String pName = paramNames.get(i);
            if (pName.isEmpty()) {
                pName = "x" + i;
            }
            pNames.add(pName);
            sb.append(delim + mtype.parameterType(i).getName() + " " + pName);
            delim = ", ";
        }
        if (varargs) {
            String lastArg = "x" + numParams;
            if (numParams > 0) {
                sb.append(", ");
            }
            sb.append("Object... " + lastArg);
            pNames.add(lastArg);
        }
        sb.append(") {\n");
        incrAlign();
        indent();
        sb.append("try {\n");
        incrAlign();
        indent();
        if (!mtype.returnType().equals(void.class)) {
            sb.append("return (" + mtype.returnType().getName() + ")");
        }
        sb.append(methodHandleGetCallString(javaName, nativeName, mtype, desc, varargs) + ".invokeExact(" + String.join(", ", pNames) + ");\n");
        decrAlign();
        indent();
        sb.append("} catch (Throwable ex) {\n");
        incrAlign();
        indent();
        sb.append("throw new AssertionError(ex);\n");
        decrAlign();
        indent();
        sb.append("}\n");
        decrAlign();
        indent();
        sb.append("}\n");
        decrAlign();
    }
}
