package io.github.jinsh1228.largelights;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import static org.junit.jupiter.api.Assertions.*;

class CmsIntegrationTest {
    @Test
    void configuredMixinsNeverTargetCmsImplementationClasses() throws Exception {
        var loader = getClass().getClassLoader();
        try (var config = loader.getResourceAsStream("create_large_lights.mixins.json")) {
            assertNotNull(config);
            var json = JsonParser.parseReader(new InputStreamReader(config, StandardCharsets.UTF_8)).getAsJsonObject();
            String base = json.get("package").getAsString();
            for (String side : new String[]{"mixins", "client"}) for (var name : json.getAsJsonArray(side)) {
                String mixin = base + "." + name.getAsString();
                try (var bytes = loader.getResourceAsStream(mixin.replace('.', '/') + ".class")) {
                    assertNotNull(bytes, mixin);
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (!descriptor.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public AnnotationVisitor visitArray(String field) { return this; }
                                @Override
                                public void visit(String field, Object value) {
                                    String target = value instanceof Type type ? type.getClassName() : String.valueOf(value);
                                    assertFalse(target.startsWith("org.haruyq.createmediasystems."), mixin + " targets " + target);
                                }
                            };
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
    }
}
