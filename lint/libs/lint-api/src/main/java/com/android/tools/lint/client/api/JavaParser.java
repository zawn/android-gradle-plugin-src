/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.lint.client.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.detector.api.Context;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Location;
import com.google.common.annotations.Beta;
import com.google.common.base.Splitter;

import java.util.List;

import lombok.ast.Identifier;
import lombok.ast.Node;
import lombok.ast.StrictListAccessor;
import lombok.ast.TypeReference;
import lombok.ast.TypeReferencePart;

/**
 * A wrapper for a Java parser. This allows tools integrating lint to map directly
 * to builtin services, such as already-parsed data structures in Java editors.
 * <p/>
 * <b>NOTE: This is not public or final API; if you rely on this be prepared
 * to adjust your code for the next tools release.</b>
 */
@Beta
public abstract class JavaParser {
    public static final String TYPE_OBJECT = "java.lang.Object";        //$NON-NLS-1$
    public static final String TYPE_STRING = "java.lang.String";        //$NON-NLS-1$
    public static final String TYPE_INT = "int";                        //$NON-NLS-1$
    public static final String TYPE_LONG = "long";                      //$NON-NLS-1$
    public static final String TYPE_CHAR = "char";                      //$NON-NLS-1$
    public static final String TYPE_FLOAT = "float";                    //$NON-NLS-1$
    public static final String TYPE_DOUBLE = "double";                  //$NON-NLS-1$
    public static final String TYPE_BOOLEAN = "boolean";                //$NON-NLS-1$
    public static final String TYPE_SHORT = "short";                    //$NON-NLS-1$
    public static final String TYPE_BYTE = "byte";                      //$NON-NLS-1$
    public static final String TYPE_NULL = "null";                      //$NON-NLS-1$

    /**
     * Prepare to parse the given contexts. This method will be called before
     * a series of {@link #parseJava(JavaContext)} calls, which allows some
     * parsers to do up front global computation in case they want to more
     * efficiently process multiple files at the same time. This allows a single
     * type-attribution pass for example, which is a lot more efficient than
     * performing global type analysis over and over again for each individual
     * file
     *
     * @param contexts a list of contexts to be parsed
     */
    public abstract void prepareJavaParse(@NonNull List<JavaContext> contexts);

    /**
     * Parse the file pointed to by the given context.
     *
     * @param context the context pointing to the file to be parsed, typically
     *            via {@link Context#getContents()} but the file handle (
     *            {@link Context#file} can also be used to map to an existing
     *            editor buffer in the surrounding tool, etc)
     * @return the compilation unit node for the file
     */
    @Nullable
    public abstract Node parseJava(@NonNull JavaContext context);

    /**
     * Returns a {@link Location} for the given node
     *
     * @param context information about the file being parsed
     * @param node the node to create a location for
     * @return a location for the given node
     */
    @NonNull
    public abstract Location getLocation(@NonNull JavaContext context, @NonNull Node node);

    /**
     * Creates a light-weight handle to a location for the given node. It can be
     * turned into a full fledged location by
     * {@link com.android.tools.lint.detector.api.Location.Handle#resolve()}.
     *
     * @param context the context providing the node
     * @param node the node (element or attribute) to create a location handle
     *            for
     * @return a location handle
     */
    @NonNull
    public abstract Location.Handle createLocationHandle(@NonNull JavaContext context,
            @NonNull Node node);

    /**
     * Dispose any data structures held for the given context.
     * @param context information about the file previously parsed
     * @param compilationUnit the compilation unit being disposed
     */
    public void dispose(@NonNull JavaContext context, @NonNull Node compilationUnit) {
    }

    /**
     * Resolves the given expression node: computes the declaration for the given symbol
     *
     * @param context information about the file being parsed
     * @param node the node to resolve
     * @return a node representing the resolved fully type: class/interface/annotation,
     *          field, method or variable
     */
    @Nullable
    public abstract ResolvedNode resolve(@NonNull JavaContext context, @NonNull Node node);

    /**
     * Gets the type of the given node
     *
     * @param context information about the file being parsed
     * @param node the node to look up the type for
     * @return the type of the node, if known
     */
    @Nullable
    public abstract TypeDescriptor getType(@NonNull JavaContext context, @NonNull Node node);

    /** A description of a type, such as a primitive int or the android.app.Activity class */
    public abstract static class TypeDescriptor {
        /**
         * Returns the fully qualified name of the type, such as "int" or "android.app.Activity"
         * */
        @NonNull public abstract String getName();

        /**
         * Returns the full signature of the type, which is normally the same as {@link #getName()}
         * but for arrays can include []'s, for generic methods can include generics parameters
         * etc
         */
        @NonNull public abstract String getSignature();

        public abstract boolean matchesName(@NonNull String name);

        public abstract boolean matchesSignature(@NonNull String signature);

        @NonNull
        public TypeReference getNode() {
            TypeReference typeReference = new TypeReference();
            StrictListAccessor<TypeReferencePart, TypeReference> parts = typeReference.astParts();
            for (String part : Splitter.on('.').split(getName())) {
                Identifier identifier = Identifier.of(part);
                parts.addToEnd(new TypeReferencePart().astIdentifier(identifier));
            }

            return typeReference;
        }
    }

    /** Convenience implementation of {@link TypeDescriptor} */
    public static class DefaultTypeDescriptor extends TypeDescriptor {
        private String mName;

        public DefaultTypeDescriptor(String name) {
            mName = name;
        }

        @NonNull
        @Override
        public String getName() {
            return mName;
        }

        @NonNull
        @Override
        public String getSignature() {
            return getName();
        }

        @Override
        public boolean matchesName(@NonNull String name) {
            return mName.equals(name);
        }

        @Override
        public boolean matchesSignature(@NonNull String signature) {
            return matchesName(signature);
        }

        @Override
        public String toString() {
            return getSignature();
        }
    }

    /** A resolved declaration from an AST Node reference */
    public abstract static class ResolvedNode {
        @NonNull
        public abstract String getName();

        /** Returns the signature of the resolved node */
        public abstract String getSignature();

        public abstract int getModifiers();

        @Override
        public String toString() {
            return getSignature();
        }
    }

    /** A resolved class declaration */
    public abstract static class ResolvedClass extends ResolvedNode {
        /** Returns the fully qualified name of this class */
        @Override
        @NonNull
        public abstract String getName();

        /** Returns whether this class' fully qualified name matches the given name */
        public abstract boolean matches(@NonNull String name);

        @Nullable
        public abstract ResolvedClass getSuperClass();

        @Nullable
        public abstract ResolvedClass getContainingClass();

        public TypeDescriptor getType() {
            return new DefaultTypeDescriptor(getName());
        }

        /**
         * Determines whether this class extends the given name. If strict is true,
         * it will not consider C extends C true.
         *
         * @param name the fully qualified class name
         * @param strict if true, do not consider a class to be extending itself
         * @return true if this class extends the given class
         */
        public abstract boolean isSubclassOf(@NonNull String name, boolean strict);

        @NonNull
        public abstract Iterable<ResolvedMethod> getConstructors();

        @NonNull
        public abstract Iterable<ResolvedMethod> getMethods(@NonNull String name);

        @Nullable
        public abstract ResolvedField getField(@NonNull String name);
    }

    /** A method or constructor declaration */
    public abstract static class ResolvedMethod extends ResolvedNode {
        @Override
        @NonNull
        public abstract String getName();

        /** Returns whether this method name matches the given name */
        public abstract boolean matches(@NonNull String name);

        @NonNull
        public abstract ResolvedClass getContainingClass();

        public abstract int getArgumentCount();

        @NonNull
        public abstract TypeDescriptor getArgumentType(int index);

        @Nullable
        public abstract TypeDescriptor getReturnType();

        public boolean isConstructor() {
            return getReturnType() == null;
        }
    }

    /** A field declaration */
    public abstract static class ResolvedField extends ResolvedNode {
        @Override
        @NonNull
        public abstract String getName();

        /** Returns whether this field name matches the given name */
        public abstract boolean matches(@NonNull String name);

        @NonNull
        public abstract TypeDescriptor getType();

        @NonNull
        public abstract ResolvedClass getContainingClass();

        @Nullable
        public abstract Object getValue();
    }

    /** A local variable or parameter declaration */
    public abstract static class ResolvedVariable extends ResolvedNode {
        @Override
        @NonNull
        public abstract String getName();

        /** Returns whether this variable name matches the given name */
        public abstract boolean matches(@NonNull String name);

        @NonNull
        public abstract TypeDescriptor getType();
    }
}
