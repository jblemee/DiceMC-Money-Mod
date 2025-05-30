package dicemc.money.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dicemc.money.MoneyMod.AcctTypes;
import dicemc.money.setup.Config;
import dicemc.money.storage.MoneyWSD;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class AccountCommandRoot implements Command<CommandSourceStack>{
	private static final AccountCommandRoot CMD = new AccountCommandRoot();
	
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("money")
				.then(AccountCommandAdmin.register(dispatcher))
				.then(AccountCommandTransfer.register(dispatcher))
				.executes(CMD));
	}

	@Override
	public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Double balP = MoneyWSD.get().getBalance(AcctTypes.PLAYER.key, context.getSource().getEntityOrException().getUUID());
		context.getSource().sendSuccess(() -> Component.literal(Config.getFormattedCurrency(balP)), false);
		return 0;
	}
	
	
}
