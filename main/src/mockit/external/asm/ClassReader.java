/*
 * ASM: a very small and fast Java bytecode manipulation framework
 * Copyright (c) 2000-2011 INRIA, France Telecom
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holders nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGE.
 */
package mockit.external.asm;

import java.io.*;
import javax.annotation.*;

import static mockit.external.asm.ConstantPoolItemType.*;

/**
 * A Java class parser to make a {@link ClassVisitor} visit an existing class.
 * This class parses a byte array conforming to the Java class file format and calls the appropriate visit methods of a
 * given class visitor for each field, method and bytecode instruction encountered.
 */
public final class ClassReader extends AnnotatedReader
{
   /**
    * Flag to skip method code. If this class is set <code>CODE</code> attribute won't be visited.
    * This can be used, for example, to retrieve annotations for methods and method parameters.
    */
   public static final int SKIP_CODE = 1;

   /**
    * Flag to skip the debug information in the class. If this flag is set the debug information of the class is not
    * visited, i.e. the {@link MethodVisitor#visitLocalVariable} and {@link MethodVisitor#visitLineNumber} methods will
    * not be called.
    */
   public static final int SKIP_DEBUG = 2;

   private static final String[] NO_INTERFACES = {};

   // Helper fields.
   ClassVisitor cv;
   private Context context;
   int access;
   private String name;
   @Nullable private String superClass;
   @Nonnull private String[] interfaces = NO_INTERFACES;
   @Nullable private String signature;
   @Nullable private String sourceFile;
   @Nullable private String sourceDebug;
   @Nullable private EnclosingMethod enclosingMethod;

   /**
    * Constructs a new {@link ClassReader} object.
    *
    * @param bytecode the bytecode of the class to be read.
    */
   public ClassReader(@Nonnull byte[] bytecode) { super(bytecode); }

   /**
    * Constructs a new {@link ClassReader} object.
    *
    * @param is an input stream from which to read the class.
    * @throws IOException if a problem occurs during reading.
    */
   public ClassReader(@Nonnull InputStream is) throws IOException {
      this(readClass(is));
   }

   @Nonnull
   private static byte[] readClass(@Nonnull InputStream is) throws IOException {
      try {
         byte[] b = new byte[is.available()];
         int len = 0;

         while (true) {
            int n = is.read(b, len, b.length - len);

            if (n == -1) {
               if (len < b.length) {
                  byte[] c = new byte[len];
                  System.arraycopy(b, 0, c, 0, len);
                  b = c;
               }

               return b;
            }

            len += n;

            if (len == b.length) {
               int last = is.read();

               if (last < 0) {
                  return b;
               }

               byte[] c = new byte[b.length + 1000];
               System.arraycopy(b, 0, c, 0, len);
               c[len++] = (byte) last;
               b = c;
            }
         }
      }
      finally {
         is.close();
      }
   }

   /**
    * Returns the classfile version of the class being read.
    */
   public int getVersion() {
      return readShort(6);
   }

   /**
    * Returns the class's access flags (see {@link Opcodes}). This value may not reflect Deprecated and Synthetic flags
    * when bytecode is before 1.5 and those flags are represented by attributes.
    *
    * @return the class access flags
    * @see ClassVisitor#visit(int, int, String, String, String, String[])
    */
   public int getAccess() {
      return readUnsignedShort(header);
   }

   /**
    * Returns the internal name of the class (see {@link JavaType#getInternalName()}).
    *
    * @return the internal class name
    * @see ClassVisitor#visit(int, int, String, String, String, String[])
    */
   public String getClassName() {
      return readClass(header + 2);
   }

   /**
    * Returns the internal of name of the super class (see {@link JavaType#getInternalName()}).
    * For interfaces, the super class is {@link Object}.
    *
    * @return the internal name of super class, or <tt>null</tt> for {@link Object} class.
    * @see ClassVisitor#visit(int, int, String, String, String, String[])
    */
   @Nullable
   public String getSuperName() {
      return readClass(header + 4);
   }

   /**
    * Returns the internal names of the class's interfaces (see {@link JavaType#getInternalName()}).
    *
    * @return the array of internal names for all implemented interfaces or <tt>null</tt>.
    * @see ClassVisitor#visit(int, int, String, String, String, String[])
    */
   @Nonnull
   public String[] getInterfaces() {
      int index = header + 6;
      int n = readUnsignedShort(index);
      String[] interfaces = new String[n];

      if (n > 0) {
         char[] buf = new char[maxStringLength];

         for (int i = 0; i < n; ++i) {
            index += 2;
            interfaces[i] = readClass(index, buf);
         }
      }

      return interfaces;
   }

   /**
    * Copies the constant pool data into the given {@link ClassWriter}.
    */
   void copyPool(@Nonnull ClassWriter cw) {
      char[] buf = new char[maxStringLength];
      int ll = items.length;
      Item[] items2 = new Item[ll];

      for (int i = 1; i < ll; i++) {
         int index = items[i];
         int tag = b[index - 1];
         Item item = new Item(i);
         int nameType;

         switch (tag) {
            case FIELD:
            case METH:
            case IMETH:
               nameType = items[readUnsignedShort(index + 2)];
               item.set(tag, readClass(index, buf), readUTF8(nameType, buf), readUTF8(nameType + 2, buf));
               break;
            case INT:
               item.set(readInt(index));
               break;
            case FLOAT:
               item.set(Float.intBitsToFloat(readInt(index)));
               break;
            case NAME_TYPE:
               item.set(tag, readUTF8(index, buf), readUTF8(index + 2, buf), null);
               break;
            case LONG:
               item.set(readLong(index));
               i++;
               break;
            case DOUBLE:
               item.set(Double.longBitsToDouble(readLong(index)));
               i++;
               break;
            case UTF8:
               copyUTF8Item(buf, i, tag, item);
               break;
            case HANDLE:
               copyHandleItem(buf, index, item);
               break;
            case INDY:
               copyInvokeDynamicItem(cw, buf, items2, index, item);
               break;
            // case STR|CLASS|MTYPE:
            default:
               item.set(tag, readUTF8(index, buf), null, null);
               break;
         }

         int index2 = item.hashCode % items2.length;
         item.next = items2[index2];
         items2[index2] = item;
      }

      int off = items[1] - 1;
      cw.cp.copy(b, off, header, items2);
   }

   private void copyUTF8Item(@Nonnull char[] buf, @Nonnegative int i, int tag, @Nonnull Item item) {
      String s = strings[i];

      if (s == null) {
         int index = items[i];
         s = strings[i] = readUTF(index + 2, readUnsignedShort(index), buf);
      }

      item.set(tag, s, null, null);
   }

   private void copyHandleItem(@Nonnull char[] buf, @Nonnegative int index, @Nonnull Item item) {
      int fieldOrMethodRef = items[readUnsignedShort(index + 1)];
      int nameType = items[readUnsignedShort(fieldOrMethodRef + 2)];

      int type = HANDLE_BASE + readByte(index);
      String classDesc = readClass(fieldOrMethodRef, buf);
      String name = readUTF8(nameType, buf);
      String desc = readUTF8(nameType + 2, buf);
      item.set(type, classDesc, name, desc);
   }

   private void copyInvokeDynamicItem(
      @Nonnull ClassWriter cw, @Nonnull char[] buf, @Nonnull Item[] items2, @Nonnegative int index, @Nonnull Item item
   ) {
      cw.bootstrapMethods.copyBootstrapMethods(this, items2, buf);

      int nameType = items[readUnsignedShort(index + 2)];
      String name = readUTF8(nameType, buf);
      String desc = readUTF8(nameType + 2, buf);
      int bsmIndex = readUnsignedShort(index);

      //noinspection ConstantConditions
      item.set(name, desc, bsmIndex);
   }

   /**
    * Makes the given visitor visit the Java class of this {@link ClassReader}, all attributes included.
    */
   public void accept(ClassVisitor cv) {
      accept(cv, 0);
   }

   /**
    * Makes the given visitor visit the Java class of this {@link ClassReader}.
    *
    * @param cv    the visitor that must visit this class.
    * @param flags option flags that can be used to modify the default behavior of this class.
    *              See {@link #SKIP_CODE}, {@link #SKIP_DEBUG}.
    */
   public void accept(@Nonnull ClassVisitor cv, int flags) {
      this.cv = cv;

      char[] c = new char[maxStringLength]; // buffer used to read strings
      context = new Context(flags, c);

      readClassDeclaration();
      readInterfaces();

      // Reads the class attributes.
      signature = null;
      sourceFile = null;
      sourceDebug = null;
      enclosingMethod = null;
      int annotations = 0;
      int innerClasses = 0;

      int u = getAttributesStartIndex();

      for (int i = readUnsignedShort(u); i > 0; i--) {
         String attrName = readUTF8(u + 2, c);

         if ("SourceFile".equals(attrName)) {
            sourceFile = readUTF8(u + 8, c);
         }
         else if ("InnerClasses".equals(attrName)) {
            innerClasses = u + 8;
         }
         else if ("EnclosingMethod".equals(attrName)) {
            enclosingMethod = new EnclosingMethod(this, c, u);
         }
         else if ("Signature".equals(attrName)) {
            signature = readUTF8(u + 8, c);
         }
         else if ("RuntimeVisibleAnnotations".equals(attrName)) {
            annotations = u + 8;
         }
         else if ("Deprecated".equals(attrName)) {
            access = Access.asDeprecated(access);
         }
         else if ("Synthetic".equals(attrName)) {
            access = Access.asSynthetic(access);
         }
         else if ("SourceDebugExtension".equals(attrName)) {
            int len = readInt(u + 4);
            sourceDebug = readUTF(u + 8, len, new char[len]);
         }
         else if ("BootstrapMethods".equals(attrName)) {
            readBootstrapMethods(u);
         }

         u += 6 + readInt(u + 4);
      }

      readClass();
      readSourceAndDebugInfo();
      readOuterClass();
      readAnnotations(annotations);
      readInnerClasses(innerClasses);
      readFieldsAndMethods();
      cv.visitEnd();
   }

   private void readClassDeclaration() {
      int u = header;
      char[] c = context.buffer;
      access = readUnsignedShort(u);
      name = readClass(u + 2, c);
      superClass = readClass(u + 4, c);
   }

   private void readInterfaces() {
      int u = header;
      int interfaceCount = readUnsignedShort(u + 6);

      if (interfaceCount == 0) {
         return;
      }

      interfaces = new String[interfaceCount];
      u += 8;

      char[] c = context.buffer;

      for (int i = 0; i < interfaceCount; i++) {
         interfaces[i] = readClass(u, c);
         u += 2;
      }
   }

   private void readClass() {
      int version = readInt(items[1] - 7);
      cv.visit(version, access, name, signature, superClass, interfaces);
   }

   private void readSourceAndDebugInfo() {
      if (context.readDebugInfo() && (sourceFile != null || sourceDebug != null)) {
         cv.visitSource(sourceFile, sourceDebug);
      }
   }

   private void readOuterClass() {
      if (enclosingMethod != null) {
         cv.visitOuterClass(enclosingMethod.owner, enclosingMethod.name, enclosingMethod.desc);
      }
   }

   private void readAnnotations(@Nonnegative int annotations) {
      if (annotations != 0) {
         char[] c = context.buffer;

         for (int i = readUnsignedShort(annotations), v = annotations + 2; i > 0; i--) {
            String desc = readUTF8(v, c);
            @SuppressWarnings("ConstantConditions") AnnotationVisitor av = cv.visitAnnotation(desc);
            v = annotationReader.readAnnotationValues(v + 2, c, true, av);
         }
      }
   }

   private void readInnerClasses(@Nonnegative int innerClasses) {
      if (innerClasses != 0) {
         int v = innerClasses + 2;
         char[] c = context.buffer;

         for (int i = readUnsignedShort(innerClasses); i > 0; i--) {
            String name = readClass(v, c);
            String outerName = readClass(v + 2, c);
            String innerName = readUTF8(v + 4, c);
            int access = readUnsignedShort(v + 6);

            //noinspection ConstantConditions
            cv.visitInnerClass(name, outerName, innerName, access);
            v += 8;
         }
      }
   }

   private void readFieldsAndMethods() {
      ClassVisitor cv = this.cv;

      FieldReader fieldReader = new FieldReader(this);
      int u = header + 8 + 2 * interfaces.length;
      int fieldCount = readUnsignedShort(u);
      u += 2;

      for (int i = fieldCount; i > 0; i--) {
         u = fieldReader.readField(cv, context, u);
      }

      MethodReader methodReader = new MethodReader(this, cv);
      int methodCount = readUnsignedShort(u);
      u += 2;

      for (int i = methodCount; i > 0; i--) {
         u = methodReader.readMethod(context, u);
      }
   }

   private void readBootstrapMethods(@Nonnegative int u) {
      int[] bootstrapMethods = new int[readUnsignedShort(u + 8)];

      for (int j = 0, v = u + 10; j < bootstrapMethods.length; j++) {
         bootstrapMethods[j] = v;
         v += 2 + readUnsignedShort(v + 2) << 1;
      }

      context.bootstrapMethods = bootstrapMethods;
   }

   /**
    * Returns the start index of the attribute_info structure of this class.
    */
   @Nonnegative
   int getAttributesStartIndex() {
      // Skips the header.
      int u = header + 8 + readUnsignedShort(header + 6) * 2;

      // Skips fields and methods.
      for (int i = readUnsignedShort(u); i > 0; i--) {
         for (int j = readUnsignedShort(u + 8); j > 0; j--) {
            u += 6 + readInt(u + 12);
         }

         u += 8;
      }

      u += 2;

      for (int i = readUnsignedShort(u); i > 0; i--) {
         for (int j = readUnsignedShort(u + 8); j > 0; j--) {
            u += 6 + readInt(u + 12);
         }

         u += 8;
      }

      // The attribute_info structure starts just after the methods.
      return u + 2;
   }
}
