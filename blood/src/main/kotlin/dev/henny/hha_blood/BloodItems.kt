package dev.henny.hha_blood

import dev.henny.hha.api.item.HhaItemHelpers
import dev.henny.hha_blood.item.BloodMaceItem
import dev.henny.hha_blood.item.BloodSwordItem
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.ToolMaterial
import net.minecraft.item.equipment.ArmorMaterial
import net.minecraft.item.equipment.EquipmentAsset
import net.minecraft.item.equipment.EquipmentAssetKeys
import net.minecraft.item.equipment.EquipmentType
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.tag.BlockTags
import net.minecraft.registry.tag.TagKey
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Rarity

object BloodItems {

    val REPAIR_TAG: TagKey<Item> = TagKey.of(RegistryKeys.ITEM, HhaBlood.id("blood_repair"))
    val EQUIPMENT_ASSET: RegistryKey<EquipmentAsset> = RegistryKey.of(EquipmentAssetKeys.REGISTRY_KEY, HhaBlood.id("blood"))

    private val DEFENSE = mapOf(
        EquipmentType.HELMET to 3,
        EquipmentType.CHESTPLATE to 8,
        EquipmentType.LEGGINGS to 6,
        EquipmentType.BOOTS to 3,
    )

    val ARMOR_MATERIAL = ArmorMaterial(40, DEFENSE, 17, SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE, 3.5f, 0.1f, REPAIR_TAG, EQUIPMENT_ASSET)
    val TOOL_MATERIAL = ToolMaterial(BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 2531, 9.0f, 5.0f, 17, REPAIR_TAG)

    lateinit var BLOOD_INGOT: Item private set
    lateinit var BLOOD_HELMET: Item private set
    lateinit var BLOOD_CHESTPLATE: Item private set
    lateinit var BLOOD_LEGGINGS: Item private set
    lateinit var BLOOD_BOOTS: Item private set
    lateinit var BLOOD_SWORD: Item private set
    lateinit var BLOOD_MACE: Item private set

    fun init() {
        BLOOD_INGOT = HhaItemHelpers.register(HhaBlood.id("blood_ingot")) { Item(it.fireproof().rarity(Rarity.RARE)) }
        BLOOD_HELMET = armor("blood_helmet", EquipmentType.HELMET)
        BLOOD_CHESTPLATE = armor("blood_chestplate", EquipmentType.CHESTPLATE)
        BLOOD_LEGGINGS = armor("blood_leggings", EquipmentType.LEGGINGS)
        BLOOD_BOOTS = armor("blood_boots", EquipmentType.BOOTS)
        BLOOD_SWORD = HhaItemHelpers.register(HhaBlood.id("blood_sword")) {
            BloodSwordItem(
                it.sword(TOOL_MATERIAL, 5.0f, -2.4f).fireproof().rarity(Rarity.EPIC)
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false),
            )
        }
        BLOOD_MACE = HhaItemHelpers.register(HhaBlood.id("blood_mace")) {
            BloodMaceItem(
                it.sword(TOOL_MATERIAL, 2.0f, -2.8f).fireproof().rarity(Rarity.EPIC)
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false),
            )
        }
        registerItemGroup()
    }

    private fun armor(name: String, type: EquipmentType): Item =
        HhaItemHelpers.registerArmor(HhaBlood.id(name), ARMOR_MATERIAL, type) {
            it.fireproof().rarity(Rarity.EPIC).component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false)
        }

    private fun registerItemGroup() {
        Registry.register(
            Registries.ITEM_GROUP,
            RegistryKey.of(RegistryKeys.ITEM_GROUP, HhaBlood.id("blood_set")),
            FabricItemGroup.builder()
                .icon { ItemStack(BLOOD_SWORD) }
                .displayName(Text.translatable("itemGroup.hha_blood.blood_set"))
                .entries { _, entries ->
                    entries.add(BLOOD_INGOT)
                    entries.add(BLOOD_HELMET)
                    entries.add(BLOOD_CHESTPLATE)
                    entries.add(BLOOD_LEGGINGS)
                    entries.add(BLOOD_BOOTS)
                    entries.add(BLOOD_SWORD)
                    entries.add(BLOOD_MACE)
                    entries.add(dev.henny.hha_blood.hollows.HollowsBlocks.BLOOD_CRYSTAL_SHARD)
                    entries.add(dev.henny.hha_blood.boss.BossContent.BLOOD_OFFERING)
                    entries.add(dev.henny.hha_blood.boss.BossContent.HEAD_BLEED)
                    entries.add(dev.henny.hha_blood.boss.BossContent.HEAD_FLAME)
                    entries.add(dev.henny.hha_blood.boss.BossContent.HEAD_HOWL)
                    for (b in dev.henny.hha_blood.hollows.HollowsBlocks.ALL) entries.add(b)
                }
                .build(),
        )
    }
}
