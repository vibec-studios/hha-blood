package dev.henny.hha_blood.client

import dev.henny.hha.api.client.HhaClientAddon
import dev.henny.hha.api.client.HhaClientAddonContext
import dev.henny.hha_blood.BloodItems
import dev.henny.hha_blood.HhaBlood
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack

/** Cooldown slots in HHA's bar above the hotbar. */
class BloodAddonClient : HhaClientAddon {

    override fun onInitializeClient(context: HhaClientAddonContext) {
        context.registerHudCooldown(HhaBlood.id("hud/tracker"), ACCENT) { p ->
            p.getEquippedStack(EquipmentSlot.HEAD).takeIf { it.isOf(BloodItems.BLOOD_HELMET) }
        }
        context.registerHudCooldown(HhaBlood.id("hud/nihil"), ACCENT) { p ->
            p.getEquippedStack(EquipmentSlot.CHEST).takeIf { it.isOf(BloodItems.BLOOD_CHESTPLATE) && hasFullBlood(p) }
        }
        context.registerHudCooldown(HhaBlood.id("hud/trail"), ACCENT) { p ->
            p.getEquippedStack(EquipmentSlot.LEGS).takeIf { it.isOf(BloodItems.BLOOD_LEGGINGS) }
        }
        context.registerHudCooldown(HhaBlood.id("hud/saws"), ACCENT) { p -> held(p, BloodItems.BLOOD_SWORD) }
        context.registerHudCooldown(HhaBlood.id("hud/cyclone"), ACCENT) { p -> held(p, BloodItems.BLOOD_MACE) }
    }

    private fun held(player: PlayerEntity, item: Item): ItemStack? =
        player.mainHandStack.takeIf { it.isOf(item) } ?: player.offHandStack.takeIf { it.isOf(item) }

    private fun hasFullBlood(p: PlayerEntity): Boolean =
        p.getEquippedStack(EquipmentSlot.HEAD).isOf(BloodItems.BLOOD_HELMET) &&
            p.getEquippedStack(EquipmentSlot.CHEST).isOf(BloodItems.BLOOD_CHESTPLATE) &&
            p.getEquippedStack(EquipmentSlot.LEGS).isOf(BloodItems.BLOOD_LEGGINGS) &&
            p.getEquippedStack(EquipmentSlot.FEET).isOf(BloodItems.BLOOD_BOOTS)

    private companion object {
        const val ACCENT = 0xB01E30
    }
}
