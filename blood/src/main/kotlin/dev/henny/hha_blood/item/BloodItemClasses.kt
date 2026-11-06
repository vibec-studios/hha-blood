package dev.henny.hha_blood.item

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Cooldowns
import dev.henny.hha_blood.logic.BloodCyclone
import dev.henny.hha_blood.logic.Bloodsaws
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.item.MaceItem
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.world.World

/** Blood Sword: right-click = Bloodsaws. */
class BloodSwordItem(settings: Settings) : Item(settings) {

    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        val stack = user.getStackInHand(hand)
        if (user.itemCooldownManager.isCoolingDown(stack) || !HhaConfig.enabled("hha_blood.bloodsaws")) return ActionResult.PASS
        if (world is ServerWorld && user is ServerPlayerEntity) {
            Bloodsaws.fire(world, user)
            Cooldowns.set(user, stack, "hha_blood.bloodsaws_cooldown")
        }
        return ActionResult.SUCCESS
    }
}

/** Blood Mace: full vanilla mace smash + right-click = Blood Cyclone. */
class BloodMaceItem(settings: Settings) : MaceItem(settings) {

    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        val stack = user.getStackInHand(hand)
        if (user.itemCooldownManager.isCoolingDown(stack) || !HhaConfig.enabled("hha_blood.blood_cyclone")) return ActionResult.PASS
        if (world is ServerWorld && user is ServerPlayerEntity) {
            if (!BloodCyclone.cast(world, user)) return ActionResult.PASS
            Cooldowns.set(user, stack, "hha_blood.cyclone_cooldown")
        }
        return ActionResult.SUCCESS
    }
}
