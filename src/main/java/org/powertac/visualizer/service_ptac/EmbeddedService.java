package org.powertac.visualizer.service_ptac;

import org.apache.commons.io.FileExistsException;
import org.powertac.common.interfaces.VisualizerProxy;
import org.powertac.visualizer.config.Constants;
import org.powertac.server.CompetitionControlService;
import org.powertac.server.CompetitionSetupService;
import org.powertac.server.LogService;
import org.powertac.visualizer.domain.File;
import org.powertac.visualizer.domain.Game;
import org.powertac.visualizer.domain.User;
import org.powertac.visualizer.domain.enumeration.FileType;
import org.powertac.visualizer.logtool.LogtoolExecutor;
import org.powertac.visualizer.service.FileService;
import org.powertac.visualizer.service.GameService;
import org.powertac.visualizer.service_ptac.VisualizerService.VisualizerState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

/**
 * This service runs Power TAC games (sim, boot and replay).
 *
 * @author Jurica Babic, Govert Buijs, Erik Kemperman
 */
@Service
public class EmbeddedService {

    @Autowired
    private FileService fileService;

    @Autowired
    private GameService gameService;

    @Autowired
    private VisualizerService visualizerService;

    @Autowired
    private MessageDispatcher messageDispatcher;

    @Autowired
    private CompetitionSetupService competitionSetupService;

    @Autowired
    private CompetitionControlService competitionControlService;

    @Autowired
    private LogService logService;

    @Autowired
    private VisualizerProxy visualizerProxy;

    private Thread replayGameThread;

    @PostConstruct
    private void afterPropertiesSet() {
        visualizerProxy.registerVisualizerMessageListener(messageDispatcher);
    }

    private Game currentGame;

    /*
     * (non-Javadoc)
     *
     * @see org.powertac.visualizer.service.ptac.VisualizerCompetitionServiceIf#
     * runBootGame(org.powertac.visualizer.domain.Game)
     */
    public String runBootGame(Game game, User user) throws FileExistsException {
        String error = checkRun();
        if (error != null) {
            return error;
        }

        visualizerService.recycleAll();
        visualizerService.setState(VisualizerState.WAITING);

        // attach boot file to boot game
        String fileName = File.getSafeName(game.getName()) + ".xml";
        File bootFile = fileService.createFile(FileType.BOOT, fileName, user);
        if (bootFile.exists()) {
            throw new FileExistsException();
        }

        game.setBootFile(bootFile);

        // set log dir to files/{user}/log
        System.setProperty("logdir", "files" + File.separator + user.getLogin() + File.separator + "log");

        // attach state/trace files to boot game
        String id = game.getName();
        String suffix = "boot-" + id;
        String base = File.getSafeName(logService.getPrefix() + "-" + suffix);
        game.setTraceFile(fileService.createFile(FileType.TRACE, base + ".trace", user));
        game.setStateFile(fileService.createFile(FileType.STATE, base + ".state", user));

        currentGame = game;

        // launch a boot session
        error = competitionSetupService.bootSession(
                game.getBootFilePath(),
                game.getConfigFilePath(),
                id,
                suffix,
                game.getSeedFilePath(),
                game.getWeatherFilePath()
        );
        if (error != null) {
            visualizerService.setState(VisualizerState.FAILED);
        }
        return error;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.powertac.visualizer.service.ptac.VisualizerCompetitionServiceIf#
     * runSimGame(org.powertac.visualizer.domain.Game)
     */
    public String runSimGame(Game game, User user) {
        String error = checkRun();
        if (error != null) {
            return error;
        }

        visualizerService.recycleAll();
        visualizerService.setState(VisualizerState.WAITING);

        // set log dir to files/{user}/log
        System.setProperty("logdir", "files" + File.separator + user.getLogin() + File.separator + "log");

        // attach state/trace files to sim game
        String id = game.getName();
        String suffix = "sim-" + id;
        String base = File.getSafeName(logService.getPrefix() + "-" + suffix);
        game.setTraceFile(fileService.createFile(FileType.TRACE, base + ".trace", user));
        game.setStateFile(fileService.createFile(FileType.STATE, base + ".state", user));

        currentGame = game;

        // launch a sim session
        error = competitionSetupService.simSession(
                game.getBootFilePath(),
                game.getConfigFilePath(),
                null,
                id,
                suffix,
                game.getBrokerList(),
                game.getSeedFilePath(),
                game.getWeatherFilePath(),
                null
        );
        if (error != null) {
            visualizerService.setState(VisualizerState.FAILED);
        }
        return error;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.powertac.visualizer.service.ptac.VisualizerCompetitionServiceIf#
     * runReplayGame(org.powertac.visualizer.domain.Game)
     */
    public String runReplayGame(File file) {
        String error = checkRun();
        if (error != null) {
            return error;
        }

        visualizerService.recycleAll();

        replayGameThread = new Thread() {
            @Override
            public void run() {
                // No SimStart and SimEnd when extracting log
                visualizerService.setState(VisualizerState.RUNNING);
                LogtoolExecutor logtoolExecutor = new LogtoolExecutor();
                logtoolExecutor.readLog(file.getPath(), messageDispatcher);
                replayGameThread = null;
                visualizerService.setState(VisualizerState.FINISHED);
            }
        };
        replayGameThread.start();

        return null;
    }

    public void closeGame() {
        // relevant for sim and boot games:
        if (currentGame != null) {
            if (competitionControlService.isRunning()) {
                competitionControlService.shutDown();
            }
            gameService.delete(currentGame.getId());
            currentGame = null;
        }

        // relevant for replay games
        if (replayGameThread != null) {
            try {
                replayGameThread.interrupt();
                replayGameThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            replayGameThread = null;
        }

        visualizerService.setState(VisualizerState.FINISHED);
    }

    private String checkRun() {
        if (visualizerService.getMode().equals(Constants.MODE_TOURNAMENT)) {
            return "Can't run game in Tournament mode";
        }
        if (competitionControlService != null && competitionControlService.isRunning() || replayGameThread != null) {
            return "Can't run game, already running";
        }
        return null;
    }

}
