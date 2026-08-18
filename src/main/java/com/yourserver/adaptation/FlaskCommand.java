package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
            sender.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Использование: /flask give <игрок> <water/poison> [количество]");
            return true;
        }

        if (!args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(ChatColor.RED + "Неизвестная подкоманда. Используйте: /flask give <игрок> <water/poison>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(ChatColor.RED + "Игрок не найден или оффлайн!");
            return true;
        }

        String type = args[2].toLowerCase();
        if (!type.equals("water") && !type.equals("poison")) {
            sender.sendMessage(ChatColor.RED + "Тип флакона должен быть 'water' или 'poison'!");
            return true;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
                if (amount < 1 || amount > 64) {
                    sender.sendMessage(ChatColor.RED + "Количество должно быть от 1 до 64!");
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Некорректное количество!");
                return true;
            }
        }

        boolean success = flaskListener.giveFlask(target, type, amount);
        if (success) {
            sender.sendMessage(ChatColor.GREEN + "Флакон " + type + " выдан игроку " + target.getName() + " в количестве " + amount);
        } else {
            sender.sendMessage(ChatColor.RED + "Не удалось выдать флакон!");
        }

        return true;
    }
}
