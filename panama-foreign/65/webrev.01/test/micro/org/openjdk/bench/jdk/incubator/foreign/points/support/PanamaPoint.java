/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.bench.jdk.incubator.foreign.points.support;

import jdk.incubator.foreign.Foreign;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.LibraryLookup;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemoryLayouts;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.SystemABI;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import static java.lang.invoke.MethodType.methodType;
import static jdk.incubator.foreign.MemoryLayout.PathElement.groupElement;
import static jdk.incubator.foreign.MemoryLayouts.*;

public class PanamaPoint implements AutoCloseable {

    public static final MemoryLayout LAYOUT = MemoryLayout.ofStruct(
        MemoryLayouts.C_INT.withOrder(ByteOrder.nativeOrder()).withName("x"),
        MemoryLayouts.C_INT.withOrder(ByteOrder.nativeOrder()).withName("y")
    );

    private static final VarHandle VH_x = LAYOUT.varHandle(int.class, groupElement("x"));
    private static final VarHandle VH_y = LAYOUT.varHandle(int.class, groupElement("y"));
    private static final MethodHandle MH_distance;
    private static final MethodHandle MH_distance_ptrs;

    static {
        try {
            SystemABI abi = Foreign.getInstance().getSystemABI();
            LibraryLookup lookup = LibraryLookup.ofLibrary(MethodHandles.lookup(), "Point");
            MH_distance = abi.downcallHandle(
                lookup.lookup("distance"),
                methodType(double.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.of(C_DOUBLE, LAYOUT, LAYOUT)
            );
            MH_distance_ptrs = abi.downcallHandle(
                lookup.lookup("distance_ptrs"),
                methodType(double.class, MemoryAddress.class, MemoryAddress.class),
                FunctionDescriptor.of(C_DOUBLE, C_POINTER, C_POINTER)
            );
        } catch (NoSuchMethodException e) {
            throw new BootstrapMethodError(e);
        }
    }

    private final MemoryAddress address;

    public PanamaPoint(int x, int y) {
        this(MemorySegment.allocateNative(LAYOUT), x, y);
    }

    public PanamaPoint(MemorySegment segment, int x, int y) {
        this(segment);
        setX(x);
        setY(y);
    }

    public PanamaPoint(MemorySegment segment) {
        this.address = segment.baseAddress();
    }

    public void setX(int x) {
        VH_x.set(address, x);
    }

    public int getX() {
        return (int) VH_x.get(address);
    }

    public void setY(int y) {
        VH_y.set(address, y);
    }

    public int getY() {
        return (int) VH_y.get(address);
    }

    public double distanceTo(PanamaPoint other) {
        try {
            return (double) MH_distance.invokeExact(address.segment(), other.address.segment());
        } catch (Throwable throwable) {
            throw new InternalError(throwable);
        }
    }

    public double distanceToPtrs(PanamaPoint other) {
        try {
            return (double) MH_distance_ptrs.invokeExact(address, other.address);
        } catch (Throwable throwable) {
            throw new InternalError(throwable);
        }
    }

    @Override
    public void close() {
        address.segment().close();
    }
}
