/*
 *  Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.  Oracle designates this
 *  particular file as subject to the "Classpath" exception as provided
 *  by Oracle in the LICENSE file that accompanied this code.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */

package java.lang.invoke;

import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;

import java.util.function.BiFunction;

/**
 * An indirect var handle can be thought of as an aggregate of the method handles implementing its supported access modes.
 * Its varform contains no method name table (given that some of the method handles composing a bound var handle might
 * not be direct). The set of method handles constituting an inditrect var handle are retrieved lazily, to minimize
 * code spinning (since not all the access modes will be used anyway).
 * Indirect var handles are useful when constructing var handle adapters - that is, an adapter var handle
 * can be constructed by extracting the method handles constituting the target var handle, adapting them
 * (using the method handle combinator API) and then repackaging the adapted method handles into a new, indirect
 * var handle.
 */
/* package */ class IndirectVarHandle extends VarHandle {

    @Stable
    private final MethodHandle[] handleMap = new MethodHandle[AccessMode.values().length];
    private final VarHandle directTarget; // cache, for performance reasons
    private final VarHandle target;
    private final BiFunction<AccessMode, MethodHandle, MethodHandle> handleFactory;

    IndirectVarHandle(VarHandle target, Class<?> value, Class<?>[] coordinates, BiFunction<AccessMode, MethodHandle, MethodHandle> handleFactory) {
        super(new VarForm(value, coordinates));
        this.handleFactory = handleFactory;
        this.target = target;
        this.directTarget = target.asDirect();
    }

    @Override
    MethodType accessModeTypeUncached(AccessMode accessMode) {
        return getMethodHandle(accessMode.ordinal()).type().dropParameterTypes(0, 1);
    }

    @Override
    boolean isDirect() {
        return false;
    }

    @Override
    VarHandle asDirect() {
        return directTarget;
    }

    @Override
    @ForceInline
    MethodHandle getMethodHandle(int mode) {
        MethodHandle handle = handleMap[mode];
        if (handle == null) {
            MethodHandle targetHandle = target.getMethodHandle(mode); // might throw UOE of access mode is not supported, which is ok
            handle = handleMap[mode] = handleFactory.apply(AccessMode.values()[mode], targetHandle);
        }
        return handle;
    }

    @Override
    public MethodHandle toMethodHandle(AccessMode accessMode) {
        return getMethodHandle(accessMode.ordinal()).bindTo(this.directTarget);
    }
}
