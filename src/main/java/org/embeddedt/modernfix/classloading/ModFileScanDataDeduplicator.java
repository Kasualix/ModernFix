package org.embeddedt.modernfix.classloading;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.forgespi.language.ModFileScanData;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.stream.Collectors;

public class ModFileScanDataDeduplicator {
    private final Interner<Type> typeInterner = Interners.newStrongInterner();

    private static Field classClazzField, parentField, interfacesField, annotationClazzField, annotationTypeField;
    private static final boolean reflectionSuccessful;

    static {
        boolean success = false;
        try {
            classClazzField = ModFileScanData.ClassData.class.getDeclaredField("clazz");
            classClazzField.setAccessible(true);
            parentField = ModFileScanData.ClassData.class.getDeclaredField("parent");
            parentField.setAccessible(true);
            interfacesField = ModFileScanData.ClassData.class.getDeclaredField("interfaces");
            interfacesField.setAccessible(true);
            annotationClazzField = ModFileScanData.AnnotationData.class.getDeclaredField("clazz");
            annotationClazzField.setAccessible(true);
            annotationTypeField = ModFileScanData.AnnotationData.class.getDeclaredField("annotationType");
            annotationTypeField.setAccessible(true);
            success = true;
        } catch(ReflectiveOperationException | RuntimeException e) {
        }
        reflectionSuccessful = success;
    }

    ModFileScanDataDeduplicator() {
    }

    private void runDeduplication() {
        ModList.get().forEachModFile(this::deduplicateFile);
    }

    private void deduplicateFile(ModFile file) {
        ModFileScanData data = file.getScanResult();
        if(data != null) {
            data.getClasses().forEach(this::deduplicateClass);
            data.getAnnotations().forEach(this::deduplicateAnnotation);
        }
    }

    private void deduplicateClass(ModFileScanData.ClassData data) {
        try {
            Type type = (Type)classClazzField.get(data);
            type = typeInterner.intern(type);
            classClazzField.set(data, type);
            type = (Type)parentField.get(data);
            type = typeInterner.intern(type);
            parentField.set(data, type);
            Set<Type> types = (Set<Type>)interfacesField.get(data);
            types = types.stream().map(typeInterner::intern).collect(Collectors.toSet());
            interfacesField.set(data, types);
        } catch(ReflectiveOperationException e) {
        }
    }

    private void deduplicateAnnotation(ModFileScanData.AnnotationData data) {
        try {
            Type type = (Type)annotationClazzField.get(data);
            type = typeInterner.intern(type);
            annotationClazzField.set(data, type);
            type = (Type)annotationTypeField.get(data);
            type = typeInterner.intern(type);
            annotationTypeField.set(data, type);
        } catch(ReflectiveOperationException e) {
        }
    }

    public static void deduplicate() {
        if(!reflectionSuccessful)
            return;
        new ModFileScanDataDeduplicator().runDeduplication();
    }
}
