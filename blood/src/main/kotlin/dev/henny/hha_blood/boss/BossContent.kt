package dev.henny.hha_blood.boss

import dev.henny.hha_blood.HhaBlood
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnGroup
import net.minecraft.item.Item
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Rarity

/** Registration of the boss content (Blood Prince entity, summoning item). */
object BossContent {
    private val PRINCE_KEY: RegistryKey<EntityType<*>> = RegistryKey.of(RegistryKeys.ENTITY_TYPE, HhaBlood.id("blood_prince"))

    @JvmField val BLOOD_PRINCE: EntityType<BloodPrinceEntity> = Registry.register(
        Registries.ENTITY_TYPE, PRINCE_KEY,
        EntityType.Builder.create(::BloodPrinceEntity, SpawnGroup.MONSTER)
            .dimensions(0.9f, 2.6f).eyeHeight(2.3f).makeFireImmune().maxTrackingRange(12)
            .build(PRINCE_KEY),
    )

    private val CRUORAX_KEY: RegistryKey<EntityType<*>> = RegistryKey.of(RegistryKeys.ENTITY_TYPE, HhaBlood.id("cruorax"))

    /** Phase 2/3: the blood wyrm (rendered 3x; one big hit box). */
    @JvmField val CRUORAX: EntityType<CruoraxEntity> = Registry.register(
        Registries.ENTITY_TYPE, CRUORAX_KEY,
        EntityType.Builder.create(::CruoraxEntity, SpawnGroup.MONSTER)
            .dimensions(5.0f, 4.2f).eyeHeight(3.4f).makeFireImmune().maxTrackingRange(16)
            .build(CRUORAX_KEY),
    )

    /** "Blutopfer" - laid on the Sanguine Altar to call the Blood Prince back after he was defeated. */
    lateinit var BLOOD_OFFERING: Item private set

    /** Trophy: the heart of Cruorax (first victory per player). */
    lateinit var HEART_OF_CRUORAX: Item private set

    fun init() {
        FabricDefaultAttributeRegistry.register(BLOOD_PRINCE, BloodPrinceEntity.createAttributes())
        FabricDefaultAttributeRegistry.register(CRUORAX, CruoraxEntity.createAttributes())
        BLOOD_OFFERING = Items.register(
            RegistryKey.of(RegistryKeys.ITEM, HhaBlood.id("blood_offering")), ::Item,
            Item.Settings().rarity(Rarity.EPIC).maxCount(16).fireproof(),
        )
        HEART_OF_CRUORAX = Items.register(
            RegistryKey.of(RegistryKeys.ITEM, HhaBlood.id("heart_of_cruorax")), ::Item,
            Item.Settings().rarity(Rarity.EPIC).maxCount(1).fireproof(),
        )
        HeartVaultArena.init()
        BossDebug.init()
    }
}
