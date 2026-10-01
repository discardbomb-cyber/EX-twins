package dev.hurtify.relicsaddon.contract;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal class-file reader (JDK only): the header, the constant pool, the class names and the
 * fields. Methods and attributes are not needed: every type a class refers to appears in its
 * constant pool, as a Class entry or inside a descriptor or signature string.
 */
public final class ClassFile {
    /** A type inside a descriptor, generic signature or annotation type: {@code Lsome/Type;} or {@code Lsome/Type<...}. */
    private static final Pattern TYPE_IN_DESCRIPTOR = Pattern.compile("L([^;<>]+)[;<]");

    public record Field(int access, String name, String descriptor) {
        public boolean isStatic() { return (access & 0x0008) != 0; }
        public boolean isFinal() { return (access & 0x0010) != 0; }
    }

    public final int minor, major, access;
    /** Internal name, for example {@code dev/hurtify/relicsaddon/RelicsAddon}. */
    public final String name;
    /** Internal name of the superclass, or null (java/lang/Object, module-info). */
    public final String superName;
    public final List<String> interfaces;
    public final List<Field> fields;
    /** Every Utf8 entry, in pool order. */
    public final List<String> utf8;
    /** The payload of every String entry (CONSTANT_String), in pool order. */
    public final List<String> strings;
    /** The name of every Class entry, in pool order. */
    public final List<String> classNames;
    /** Utf8 entries that are not the payload of a String entry. */
    private final List<String> descriptive;

    private ClassFile(int minor, int major, int access, String name, String superName, List<String> interfaces, List<Field> fields,
            List<String> utf8, List<String> strings, List<String> classNames, List<String> descriptive) {
        this.minor = minor;
        this.major = major;
        this.access = access;
        this.name = name;
        this.superName = superName;
        this.interfaces = interfaces;
        this.fields = fields;
        this.utf8 = utf8;
        this.strings = strings;
        this.classNames = classNames;
        this.descriptive = descriptive;
    }

    public static ClassFile read(Path file) throws IOException {
        try {
            return parse(Files.readAllBytes(file));
        } catch (IOException | RuntimeException error) {
            throw new IOException("Cannot read class file " + file + ": " + error.getMessage(), error);
        }
    }

    public static ClassFile parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("Bad magic");
        int minor = in.readUnsignedShort(), major = in.readUnsignedShort();
        int count = in.readUnsignedShort();
        int[] tags = new int[count], refs = new int[count];
        String[] text = new String[count];
        for (int index = 1; index < count; index++) {
            int tag = in.readUnsignedByte();
            tags[index] = tag;
            switch (tag) {
                case 1 -> text[index] = in.readUTF();
                case 3, 4 -> in.skipNBytes(4);
                case 5, 6 -> {
                    in.skipNBytes(8);
                    index++;
                }
                case 7, 8, 16, 19, 20 -> refs[index] = in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 15 -> in.skipNBytes(3);
                default -> throw new IOException("Unknown constant pool tag " + tag + " at index " + index);
            }
        }
        int access = in.readUnsignedShort();
        String name = className(text, tags, refs, in.readUnsignedShort());
        int superIndex = in.readUnsignedShort();
        String superName = superIndex == 0 ? null : className(text, tags, refs, superIndex);
        int interfaceCount = in.readUnsignedShort();
        List<String> interfaces = new ArrayList<>(interfaceCount);
        for (int index = 0; index < interfaceCount; index++) interfaces.add(className(text, tags, refs, in.readUnsignedShort()));
        int fieldCount = in.readUnsignedShort();
        List<Field> fields = new ArrayList<>(fieldCount);
        for (int index = 0; index < fieldCount; index++) {
            int flags = in.readUnsignedShort();
            String fieldName = text[in.readUnsignedShort()], descriptor = text[in.readUnsignedShort()];
            int attributes = in.readUnsignedShort();
            for (int attribute = 0; attribute < attributes; attribute++) {
                in.readUnsignedShort();
                in.skipNBytes(in.readInt() & 0xFFFFFFFFL);
            }
            fields.add(new Field(flags, fieldName, descriptor));
        }

        boolean[] payload = new boolean[count];
        List<String> utf8 = new ArrayList<>(), strings = new ArrayList<>(), classNames = new ArrayList<>(), descriptive = new ArrayList<>();
        for (int index = 1; index < count; index++) {
            if (tags[index] == 8) {
                payload[refs[index]] = true;
                strings.add(text[refs[index]]);
            } else if (tags[index] == 7) {
                classNames.add(text[refs[index]]);
            }
        }
        for (int index = 1; index < count; index++) {
            if (tags[index] != 1) continue;
            utf8.add(text[index]);
            if (!payload[index]) descriptive.add(text[index]);
        }
        return new ClassFile(minor, major, access, name, superName, List.copyOf(interfaces), List.copyOf(fields),
                Collections.unmodifiableList(utf8), Collections.unmodifiableList(strings), Collections.unmodifiableList(classNames),
                Collections.unmodifiableList(descriptive));
    }

    private static String className(String[] text, int[] tags, int[] refs, int index) throws IOException {
        if (index <= 0 || index >= tags.length || tags[index] != 7) throw new IOException("Constant " + index + " is not a class");
        return text[refs[index]];
    }

    /**
     * Every type this class names: (a) each Class entry that is not an array, and (b) each
     * {@code L...;} or {@code L...<} match in a Utf8 entry that is not a String payload (descriptors,
     * generic signatures, annotation types, lambda and method-handle types).
     */
    public Set<String> referencedTypes() {
        Set<String> result = new LinkedHashSet<>();
        for (String className : classNames) if (!className.startsWith("[")) result.add(className);
        for (String entry : descriptive) {
            Matcher matcher = TYPE_IN_DESCRIPTOR.matcher(entry);
            while (matcher.find()) result.add(matcher.group(1));
        }
        return result;
    }

    public boolean topLevel() {
        return name.indexOf('$') < 0;
    }

    @Override public String toString() {
        return name;
    }
}
