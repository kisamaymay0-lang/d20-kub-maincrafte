package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RollbackCommand implements CommandExecutor {

    private final RollbackListener rollbackListener;

    public RollbackCommand(RollbackListener rollbackListener) {
        this.rollbackListener = rollbackListener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("rollback.admin")) {
            MessageUtils.send(sender, "rollback.no-perm");
            return true;
        }

        if (args.length == 0) {
            MessageUtils.send(sender, "rollback.usage");
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (args.length < 2) {
                MessageUtils.send(sender, "rollback.give-usage");
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);
            if (target == null || !target.isOnline()) {
                MessageUtils.send(sender, "rollback.player-not-found");
                return true;
            }

            boolean success = rollbackListener.giveRollbackTotem(target);
            if (success) {
                MessageUtils.send(sender, "rollback.given",
                        "{player}", target.getName());
            } else {
                MessageUtils.send(sender, "rollback.give-fail");
            }
            return true;
        }

        MessageUtils.send(sender, "rollback.unknown-sub");
        return true;
    }
}
