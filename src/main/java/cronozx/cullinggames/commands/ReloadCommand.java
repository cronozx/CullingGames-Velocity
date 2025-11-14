package cronozx.cullinggames.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.RawCommand;
import cronozx.cullinggames.CullingGames;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReloadCommand implements RawCommand {
    Logger logger = LoggerFactory.getLogger(ReloadCommand.class);

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

        CullingGames.getWhitelistServers(logger);

        source.sendMessage(Component.newline().content("§4§lCulling Games §8§l>> §r§7Server whitelist reloaded"));
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return invocation.source().hasPermission("cullinggamesvelocity.reload");
    }
}
