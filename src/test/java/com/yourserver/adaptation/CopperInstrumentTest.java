package com.yourserver.adaptation;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Медные блоки играют трубой, звук которой зависит от ступени окисления. */
final class CopperInstrumentTest {

    @Test
    void каждаяСтупеньМедиДаётСвоюТрубу() {
        assertEquals(CopperBlockListener.KEY_TRUMPET, CopperBlockListener.instrumentKey(Material.COPPER_BLOCK));
        assertEquals(CopperBlockListener.KEY_TRUMPET_EXPOSED,
                CopperBlockListener.instrumentKey(Material.EXPOSED_COPPER));
        assertEquals(CopperBlockListener.KEY_TRUMPET_WEATHERED,
                CopperBlockListener.instrumentKey(Material.WEATHERED_COPPER));
        assertEquals(CopperBlockListener.KEY_TRUMPET_OXIDIZED,
                CopperBlockListener.instrumentKey(Material.OXIDIZED_COPPER));
    }

    @Test
    void всеМедныеБлокиРаспознаютсяАНеТолькоБлок() {
        // Медные формы: резная, гранёная, решётка, лампа, ступени, плиты.
        Material[] forms = {
                Material.CUT_COPPER,
                Material.CHISELED_COPPER,
                Material.COPPER_GRATE,
                Material.COPPER_BULB,
                Material.CUT_COPPER_STAIRS,
                Material.CUT_COPPER_SLAB
        };

        for (Material form : forms) {
            assertEquals(CopperBlockListener.KEY_TRUMPET, CopperBlockListener.instrumentKey(form),
                    form + " должен играть трубой");
        }
    }

    @Test
    void вощёнаяМедьЗвучитКакСтупеньПодВоском() {
        assertEquals(CopperBlockListener.KEY_TRUMPET,
                CopperBlockListener.instrumentKey(Material.WAXED_COPPER_BLOCK));
        assertEquals(CopperBlockListener.KEY_TRUMPET_EXPOSED,
                CopperBlockListener.instrumentKey(Material.WAXED_EXPOSED_COPPER));
        assertEquals(CopperBlockListener.KEY_TRUMPET_WEATHERED,
                CopperBlockListener.instrumentKey(Material.WAXED_WEATHERED_COPPER));
        assertEquals(CopperBlockListener.KEY_TRUMPET_OXIDIZED,
                CopperBlockListener.instrumentKey(Material.WAXED_OXIDIZED_COPPER));
    }

    @Test
    void медьНеБлокНеСтановитсяТрубой() {
        assertNotEquals(CopperBlockListener.KEY_TRUMPET, CopperBlockListener.instrumentKey(Material.COPPER_ORE));
        assertNotEquals(CopperBlockListener.KEY_TRUMPET,
                CopperBlockListener.instrumentKey(Material.DEEPSLATE_COPPER_ORE));
        assertNotEquals(CopperBlockListener.KEY_TRUMPET, CopperBlockListener.instrumentKey(Material.COPPER_INGOT));
        assertNotEquals(CopperBlockListener.KEY_TRUMPET,
                CopperBlockListener.instrumentKey(Material.COPPER_DOOR));
    }

    @Test
    void остальныеИнструментыОсталисьКакУВанили() {
        assertEquals(CopperBlockListener.KEY_HARP, CopperBlockListener.instrumentKey(Material.DIRT));
        assertEquals(CopperBlockListener.KEY_BELL, CopperBlockListener.instrumentKey(Material.GOLD_BLOCK));
        assertEquals(CopperBlockListener.KEY_XYLOPHONE, CopperBlockListener.instrumentKey(Material.BONE_BLOCK));
        assertEquals(CopperBlockListener.KEY_FLUTE, CopperBlockListener.instrumentKey(Material.CLAY));
        assertEquals(CopperBlockListener.KEY_CHIME, CopperBlockListener.instrumentKey(Material.PACKED_ICE));
        assertEquals(CopperBlockListener.KEY_GUITAR, CopperBlockListener.instrumentKey(Material.WHITE_WOOL));
        assertEquals(CopperBlockListener.KEY_IRON_XYLOPHONE,
                CopperBlockListener.instrumentKey(Material.IRON_BLOCK));
        assertEquals(CopperBlockListener.KEY_COW_BELL, CopperBlockListener.instrumentKey(Material.SOUL_SAND));
        assertEquals(CopperBlockListener.KEY_DIDGERIDOO, CopperBlockListener.instrumentKey(Material.PUMPKIN));
        assertEquals(CopperBlockListener.KEY_BIT, CopperBlockListener.instrumentKey(Material.EMERALD_BLOCK));
        assertEquals(CopperBlockListener.KEY_BANJO, CopperBlockListener.instrumentKey(Material.HAY_BLOCK));
        assertEquals(CopperBlockListener.KEY_PLING, CopperBlockListener.instrumentKey(Material.GLOWSTONE));
        assertEquals(CopperBlockListener.KEY_BASS, CopperBlockListener.instrumentKey(Material.OAK_PLANKS));
        assertEquals(CopperBlockListener.KEY_BASS, CopperBlockListener.instrumentKey(Material.CHEST));
        assertEquals(CopperBlockListener.KEY_HAT, CopperBlockListener.instrumentKey(Material.GLASS));
        assertEquals(CopperBlockListener.KEY_SNARE, CopperBlockListener.instrumentKey(Material.SAND));
        assertEquals(CopperBlockListener.KEY_SNARE,
                CopperBlockListener.instrumentKey(Material.WHITE_CONCRETE_POWDER));
        assertEquals(CopperBlockListener.KEY_BASEDRUM, CopperBlockListener.instrumentKey(Material.STONE));
        assertEquals(CopperBlockListener.KEY_BASEDRUM, CopperBlockListener.instrumentKey(Material.DEEPSLATE));
        assertEquals(CopperBlockListener.KEY_SKELETON,
                CopperBlockListener.instrumentKey(Material.SKELETON_SKULL));
        assertEquals(CopperBlockListener.KEY_WITHER_SKELETON,
                CopperBlockListener.instrumentKey(Material.WITHER_SKELETON_SKULL));
        assertEquals(CopperBlockListener.KEY_ZOMBIE, CopperBlockListener.instrumentKey(Material.ZOMBIE_HEAD));
    }

    @Test
    void песчаникНеСчитаетсяПеском() {
        assertEquals(CopperBlockListener.KEY_SNARE, CopperBlockListener.instrumentKey(Material.RED_SAND));
        assertEquals(CopperBlockListener.KEY_BASEDRUM,
                CopperBlockListener.instrumentKey(Material.SANDSTONE));
        assertEquals(CopperBlockListener.KEY_COW_BELL,
                CopperBlockListener.instrumentKey(Material.SOUL_SAND));
    }

    @Test
    void головыНеПопадаютВЧужиеГруппы() {
        // Череп содержит "SKULL", зомби-голова — "HEAD": ни то, ни другое не
        // должно уехать в группу дерева или костей.
        assertEquals(CopperBlockListener.KEY_WITHER_SKELETON,
                CopperBlockListener.instrumentKey(Material.WITHER_SKELETON_WALL_SKULL));
        assertEquals(CopperBlockListener.KEY_ZOMBIE,
                CopperBlockListener.instrumentKey(Material.ZOMBIE_WALL_HEAD));
    }
}
