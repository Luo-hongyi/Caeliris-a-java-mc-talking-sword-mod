package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;

import java.util.EnumSet;

record EpisodeIntentProfile(
        PlayerIntent primaryIntent,
        double primaryScore,
        PlayerIntent secondaryIntent,
        double secondaryScore,
        EnumSet<PlayerIntent> activeIntents) {
}
