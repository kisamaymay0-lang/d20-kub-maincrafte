package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class FlaskCommand implements CommandExecutor {

    private final FlaskListener flaskListener;

    public FlaskCommand(FlaskListener flaskListener) {
        this.flaskListener = flaskListener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("flask.admin")) {
            MessageUtils.send(sender, "flask.no-perm");
            return true;
        }

        if (args.length < 3) {
            MessageUtils.send(sender, "flask.usage");
            return true;
        }

        if (!args[0].equalsIgnoreCase("give")) {
            MessageUtils.send(sender, "flask.unknown-sub");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            MessageUtils.send(sender, "flask.player-not-found");
            return true;
        }

        String type = args[2].toLowerCase();
        if (!type.equals("water") && !type.equals("poison")) {
            MessageUtils.send(sender, "flask.bad-type");
            return true;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
                if (amount < 1 || amount > 64) {
                    MessageUtils.send(sender, "flask.bad-amount");
                    return true;
                }
            } catch (NumberFormatException e) {
                MessageUtils.send(sender, "flask.bad-number");
                return true;
            }
        }

        boolean success = flaskListener.giveFlask(target, type, amount);
        if (success) {
            MessageUtils.send(sender, "flask.given",
                    "{type}", type,
                    "{player}", target.getName(),
                    "{amount}", String.valueOf(amount));
        } else {
            MessageUtils.send(sender, "flask.give-fail");
        }

        return true;
    }
}
