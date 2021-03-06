/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.MemoryLayout.PathElement;
import jdk.incubator.foreign.SystemABI;
import jdk.incubator.jextract.Type;
import org.testng.annotations.Test;

import static jdk.incubator.foreign.SystemABI.NATIVE_TYPE;
import static org.testng.Assert.assertEquals;
import static test.jextract.struct.struct_h.*;

/*
 * @test
 * @library ..
 * @modules jdk.incubator.jextract
 * @run driver JtregJextract -l Struct -t test.jextract.struct -- struct.h
 * @run testng/othervm -Dforeign.restricted=permit LibStructTest
 */
public class LibStructTest {
    @Test
    public void testMakePoint() {
        try (var seg = makePoint(42, -39)) {
            var addr = seg.baseAddress();
            assertEquals(CPoint.x$get(addr), 42);
            assertEquals(CPoint.y$get(addr), -39);
        }
    }

    @Test
    public void testAllocate() {
        try (var seg = CPoint.allocate()) {
            var addr = seg.baseAddress();
            CPoint.x$set(addr, 56);
            CPoint.y$set(addr, 65);
            assertEquals(CPoint.x$get(addr), 56);
            assertEquals(CPoint.y$get(addr), 65);
        }
    }

    @Test
    public void testAllocateArray() {
        try (var seg = CPoint.allocateArray(3)) {
            var addr = seg.baseAddress();
            for (int i = 0; i < 3; i++) {
                CPoint.x$set(addr, i, 56 + i);
                CPoint.y$set(addr, i, 65 + i);
            }
            for (int i = 0; i < 3; i++) {
                assertEquals(CPoint.x$get(addr, i), 56 + i);
                assertEquals(CPoint.y$get(addr, i), 65 + i);
            }
        }
    }

    private static void checkFieldABIType(GroupLayout group, String fieldName, Type.Primitive.Kind expected) {
        assertEquals(group.select(PathElement.groupElement(fieldName)).attribute(Type.Primitive.Kind.JEXTRACT_TYPE)
                                                                      .map(Type.Primitive.Kind.class::cast)
                                                                      .orElseThrow(), expected);
    }

    @Test
    public void testFieldTypes() {
        GroupLayout g = (GroupLayout)CAllTypes.$LAYOUT();
        checkFieldABIType(g, "sc", Type.Primitive.Kind.Char);
        checkFieldABIType(g, "uc", Type.Primitive.Kind.Char);
        checkFieldABIType(g, "s",  Type.Primitive.Kind.Short);
        checkFieldABIType(g, "us", Type.Primitive.Kind.Short);
        checkFieldABIType(g, "i",  Type.Primitive.Kind.Int);
        checkFieldABIType(g, "ui", Type.Primitive.Kind.Int);
        checkFieldABIType(g, "l",  Type.Primitive.Kind.Long);
        checkFieldABIType(g, "ul", Type.Primitive.Kind.Long);
        checkFieldABIType(g, "ll", Type.Primitive.Kind.LongLong);
        checkFieldABIType(g, "ull",Type.Primitive.Kind.LongLong);
        checkFieldABIType(g, "f",  Type.Primitive.Kind.Float);
        checkFieldABIType(g, "d",  Type.Primitive.Kind.Double);
        checkFieldABIType(g, "ld", Type.Primitive.Kind.LongDouble);
    }
}
