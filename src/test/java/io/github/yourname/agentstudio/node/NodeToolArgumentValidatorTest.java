package io.github.yourname.agentstudio.node;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NodeToolArgumentValidatorTest {

    @Test
    void acceptsAnAbsoluteSupportedWallpaperPath() {
        NodeToolArgumentValidator.validate(
                "system.desktop.set_wallpaper", Map.of("path", "C:\\Windows\\Web\\Wallpaper\\Windows\\img0.jpg"));
    }

    @Test
    void rejectsRelativeOrUnsupportedWallpaperPaths() {
        assertThatThrownBy(() -> NodeToolArgumentValidator.validate(
                "system.desktop.set_wallpaper", Map.of("path", "wallpaper.png")))
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> NodeToolArgumentValidator.validate(
                "system.desktop.set_wallpaper", Map.of("path", "C:\\temp\\wallpaper.gif")))
                .hasMessageContaining("JPG");
    }
}
