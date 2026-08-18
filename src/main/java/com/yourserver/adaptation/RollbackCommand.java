package com.yourserver.adaptation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
            sender.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Использование: /rollback give <игрок>");
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Укажите имя игрока! Пример: /rollback give PlayerName");
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(ChatColor.RED + "Игрок не найден или оффлайн!");
                return true;
            }

            boolean success = rollbackListener.giveRollbackTotem(target);
            if (success) {
                sender.sendMessage(ChatColor.GREEN + "Тотем Отката выдан игроку " + target.getName());
            } else {
                sender.sendMessage(ChatColor.RED + "Не удалось выдать тотем!");
            }
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Неизвестная подкоманда. Используйте: /rollback give <игрок>");
        return true;
    }
}
