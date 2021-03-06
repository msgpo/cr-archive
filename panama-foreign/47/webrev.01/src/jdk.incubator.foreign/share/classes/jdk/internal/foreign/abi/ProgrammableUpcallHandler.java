/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package jdk.internal.foreign.abi;

import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryHandles;
import jdk.internal.foreign.MemoryAddressImpl;
import jdk.internal.foreign.Utils;
import jdk.internal.vm.annotation.Stable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

import static sun.security.action.GetBooleanAction.privilegedGetProperty;

/**
 * This class implements upcall invocation from native code through a so called 'universal adapter'. A universal upcall adapter
 * takes an array of storage pointers, which describes the state of the CPU at the time of the upcall. This can be used
 * by the Java code to fetch the upcall arguments and to store the results to the desired location, as per system ABI.
 */
public class ProgrammableUpcallHandler implements UpcallHandler {

    private static final boolean DEBUG =
        privilegedGetProperty("jdk.internal.foreign.ProgrammableUpcallHandler.DEBUG");

    private static final VarHandle VH_LONG = MemoryHandles.varHandle(long.class, ByteOrder.nativeOrder());

    @Stable
    private final MethodHandle mh;
    private final MethodType type;
    private final FunctionDescriptor function;
    private final CallingSequence callingSequence;
    private final long entryPoint;

    private final ABIDescriptor abi;
    private final BufferLayout layout;

    public ProgrammableUpcallHandler(ABIDescriptor abi, MethodHandle target, CallingSequence callingSequence) {
        this.abi = abi;
        this.layout = BufferLayout.of(abi);
        this.type = callingSequence.methodType();
        this.function = callingSequence.functionDesc();
        this.callingSequence = callingSequence;
        this.mh = target.asSpreader(Object[].class, callingSequence.methodType().parameterCount());
        this.entryPoint = allocateUpcallStub(abi, layout);
    }

    @Override
    public long entryPoint() {
        return entryPoint;
    }

    public static void invoke(ProgrammableUpcallHandler handler, long address) {
        handler.invoke(MemoryAddress.ofLong(address));
    }

    private void invoke(MemoryAddress buffer) {
        try {
            if (DEBUG) {
                System.err.println("Buffer state before:");
                layout.dump(abi.arch, buffer, System.err);
            }

            MemoryAddress bufferBase = Utils.resizeNativeAddress(buffer, layout.size);
            MemoryAddress stackArgsBase = MemoryAddressImpl.ofLongUnchecked((long)VH_LONG.get(buffer.rebase(bufferBase.segment()).addOffset(layout.stack_args)));
            Object[] args = new Object[type.parameterCount()];
            for (int i = 0 ; i < type.parameterCount() ; i++) {
                args[i] = jdk.internal.foreign.abi.BindingInterpreter.box(callingSequence.argumentBindings(i),
                        s -> abi.arch.isStackType(s.type())
                            ? stackArgsBase.addOffset(s.index() * abi.arch.typeSize(abi.arch.stackType()))
                            : bufferBase.addOffset(layout.argOffset(s)));
            }

            if (DEBUG) {
                System.err.println("Java arguments:");
                System.err.println(Arrays.toString(args).indent(2));
            }

            Object o = mh.invoke(args);

            if (DEBUG) {
                System.err.println("Java return:");
                System.err.println(Objects.toString(o).indent(2));
            }

            if (mh.type().returnType() != void.class) {
                jdk.internal.foreign.abi.BindingInterpreter.unbox(o,
                        callingSequence.returnBindings(), s -> bufferBase.addOffset(layout.retOffset(s)), new ArrayList<>());
            }

            if (DEBUG) {
                System.err.println("Buffer state after:");
                layout.dump(abi.arch, buffer, System.err);
            }
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public native long allocateUpcallStub(ABIDescriptor abi, BufferLayout layout);

    private static native void registerNatives();
    static {
        registerNatives();
    }
}
