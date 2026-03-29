package com.pokergame.dto.response;

import com.pokergame.model.Card;
import com.pokergame.enums.GamePhase;

import java.util.List;

public record PublicGameStateResponse(
                int maxPlayers,
                int pot,
                List<Integer> pots,
                Integer uncalledAmount,
                GamePhase phase,
                int currentBet,
                List<Card> communityCards,
                List<PublicPlayerState> players,
                String currentPlayerName,
                String currentPlayerId,
                // For showdowns
                List<String> winners,
                // These use objects so they can be null
                Integer winningsPerPlayer,
                // For auto advancing
                Boolean isAutoAdvancing,
                String autoAdvanceMessage,
                // Claim-win metadata
                Boolean claimWinAvailable,
                String claimWinPlayerName) {

        // For normal game state that isn't a showdown and isn't auto advance
        public PublicGameStateResponse(int maxPlayers,
                        int pot,
                        List<Integer> pots,
                        Integer uncalledAmount,
                        GamePhase phase,
                        int currentBet,
                        List<Card> communityCards,
                        List<PublicPlayerState> players,
                        String currentPlayerName,
                        String currentPlayerId) {
                this(
                                maxPlayers,
                                pot,
                                pots,
                                uncalledAmount,
                                phase,
                                currentBet,
                                communityCards,
                                players,
                                currentPlayerName,
                                currentPlayerId,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null);

        }

        // For normal game state with claim-win metadata
        public PublicGameStateResponse(int maxPlayers,
                        int pot,
                        List<Integer> pots,
                        Integer uncalledAmount,
                        GamePhase phase,
                        int currentBet,
                        List<Card> communityCards,
                        List<PublicPlayerState> players,
                        String currentPlayerName,
                        String currentPlayerId,
                        Boolean claimWinAvailable,
                        String claimWinPlayerName) {
                this(
                                maxPlayers,
                                pot,
                                pots,
                                uncalledAmount,
                                phase,
                                currentBet,
                                communityCards,
                                players,
                                currentPlayerName,
                                currentPlayerId,
                                null,
                                null,
                                null,
                                null,
                                claimWinAvailable,
                                claimWinPlayerName);

        }
        // For a normal showdown

        public PublicGameStateResponse(int maxPlayers,
                        int pot,
                        List<Integer> pots,
                        Integer uncalledAmount,
                        GamePhase phase,
                        int currentBet,
                        List<Card> communityCards,
                        List<PublicPlayerState> players,
                        String currentPlayerName,
                        String currentPlayerId,
                        List<String> winners,
                        Integer winningsPerPlayer) {
                this(
                                maxPlayers,
                                pot,
                                pots,
                                uncalledAmount,
                                phase,
                                currentBet,
                                communityCards,
                                players,
                                currentPlayerName,
                                currentPlayerId,
                                winners,
                                winningsPerPlayer,
                                null,
                                null,
                                null,
                                null);

        }
}
