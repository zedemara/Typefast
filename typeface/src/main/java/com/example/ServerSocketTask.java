package com.example;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

public class ServerSocketTask implements Runnable {

    private Socket connection;
    private final List<User> usersList;
    private final List<ScoreObject> scoreList;
    private static final List<Team> teamList = Collections.synchronizedList(new ArrayList<>());
    private int bestScore;
    private int tgc = 0;
    private String response = null;
    private boolean startUp = true;
    private int initialTimer = 30000; // delay in millis
    private static final ConcurrentHashMap<Integer, String> currentWords = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Long> wordStartTimes = new ConcurrentHashMap<>();
    private static final List<String> WORDS = List.of("cat", "animal", "umbrella", "acronym", "difficult", "synchronous", "appropriation", "sophisticated", "apprenticeship", "designation");
    private static final ConcurrentHashMap<Integer, Integer> teamScores = new ConcurrentHashMap<>();

    public ServerSocketTask(Socket s, List<User> usersList, List<ScoreObject> scoreList, int bestScore) {
        this.connection = s;
        this.usersList = usersList;
        this.scoreList = scoreList;
        this.bestScore = bestScore;
    }

    @Override
    public void run() {
        try (ObjectInputStream ois = new ObjectInputStream(connection.getInputStream());
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream()));
             BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {

            System.out.println("connected");

            while (true) {
                User receivedUser = (User) ois.readObject();
                receivedUser.setBufferedWriter(bw); // Set the BufferedWriter for the user
                String request = (String) ois.readObject();
                System.out.println("Received request: " + request);

                if (request == null) break;

                switch (request) {
                    case "register":
                        register(receivedUser);
                        response = receivedUser.getUsername() + " is Registered Successfully";
                        sendResponse(bw, response);
                        break;

                    case "login":
                        boolean verified = verify(receivedUser);
                        sendResponse(bw, Boolean.toString(verified));
                        if (verified) {
                            updateLoginStatus(receivedUser);
                        }
                        break;

                    case "make a team":
                        handleTeamMaking(receivedUser, bw);
                        break;

                    case "start a game":
                        handleStartGame(receivedUser,request, bw, ois);
                        break;

                    case "next round":
                        handleAnswerSubmission(receivedUser,request, ois, bw);
                        break;

                    case "spectate":
                        handleSpectate(receivedUser, bw);
                        break;

                    case "q":
                        return;

                    default:
                        // Handle unknown requests
                        break;
                }

                Thread.sleep(300);
            }

        } catch (IOException | ClassNotFoundException | InterruptedException e) {
            e.printStackTrace();
        } finally {
            try {
                connection.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private synchronized void register(User user) {
        usersList.add(user);
    }

    private synchronized boolean verify(User user) {
        for (User u : usersList) {
            if (u.getUsername().equalsIgnoreCase(user.getUsername()) &&
                u.getPassword().equals(user.getPassword())) {
                return true;
            }
        }
        return false;
    }

    private synchronized void updateLoginStatus(User user) {
        for (User u : usersList) {
            if (u.getUsername().equals(user.getUsername())) {
                u.setLoggedIn(true);
            }
        }
    }

    private synchronized void handleTeamMaking(User user, BufferedWriter bw) throws IOException {
        // Initialize the teamList if this is the first time
        if (startUp) {
            Team firstTeam = new Team();
            teamList.add(firstTeam);
            startUp = false;
        }

        user.setTeamID(tgc);
        teamList.get(tgc).addUser(user);

        if (teamList.get(tgc).isFull()) {
            for (User teamUser : teamList.get(tgc).getTeamUsers()) {
                teamUser.setTeamd(true);
                sendResponse(teamUser.getBufferedWriter(), String.valueOf(tgc));
            }
            tgc += 1;
            Team newTeam = new Team();
            teamList.add(newTeam);
        } else {
            sendResponse(bw, "Waiting for team members...");
        }
    }

    private void handleStartGame(User user,String request, BufferedWriter bw, ObjectInputStream ois) throws IOException, ClassNotFoundException {
        Team team = teamList.get(user.getTeamID());
        user.setReady(true); // Set user as ready
        sendResponse(bw, "Waiting for all team members to be ready...");

        while (true) {
            boolean allReady = team.getTeamUsers().stream().allMatch(User::isReady);
            if (allReady) {
                sendResponse(user.getBufferedWriter(), "Game started for team " + team.getTeamID());
                sendNewWordToUser(user);
                handleAnswerSubmission(user,request, ois, bw);
                break;
            }
        }
    }

    private void sendNewWordToUser(User user) throws IOException {
        user.setCurrentLevel(user.getCurrentLevel() + 1);
        String newWord = WORDS.get(user.getCurrentLevel() % WORDS.size());
        currentWords.put(user.getTeamID(), newWord);
        wordStartTimes.put(user.getTeamID(), System.currentTimeMillis());
        sendResponse(user.getBufferedWriter(), "New word: " + newWord + ". You have " + initialTimer / 1000 + " seconds!");
    }

    private void handleAnswerSubmission(User user,String request, ObjectInputStream ois, BufferedWriter bw) throws IOException, ClassNotFoundException {
        int teamID = user.getTeamID();
        Team team = teamList.get(teamID);
        NonBlockingTimer timer = new NonBlockingTimer();

        user.setInGame(true);

        while (user.inGame()) {
            String correctWord = currentWords.get(teamID);
            Runnable timeoutTask = () -> {
                try {
                    sendResponse(user.getBufferedWriter(), "Time out!");
                    sendNewWordToUser(user);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            };

            timer.startTimer(timeoutTask, initialTimer);

            String answer = (String) ois.readObject();
            timer.stopTimer(); // Stop the timer if answer is received
            
            if (answer.equalsIgnoreCase("q")){
                request = "spectate";
                break;
            } else if (answer.equalsIgnoreCase(correctWord)) {
                long responseTime = System.currentTimeMillis() - wordStartTimes.get(user.getTeamID());
                user.setLastResponseTime(responseTime);
                user.setCorrectWordCount(user.getCorrectWordCount() + 1);
                sendResponse(bw, "Correct! Time: " + responseTime + "ms");

                // Check if all players in the team have answered correctly
                int count = user.getCorrectWordCount();
                
                while (true) {
                boolean allAnswered = team.getInGameUsers().stream().allMatch(u -> u.getCorrectWordCount() == count);
                if (allAnswered) {
                    sendResponse(bw, "All your team answered! You got 1 point");
                    request = "next round";
                    sendNewWordToUser(user); // Send a new word for the next round
                    break;
                }
                }
            } else {
                sendResponse(bw, "Incorrect. Try again.");
                request = "wrong answer";

            }
        }
    }

    private void handleSpectate(User user, BufferedWriter bw) throws IOException {
        sendResponse(bw, "You are now spectating.");
        while (user.isSpectator()) {
            // Send periodic updates to the spectator
            sendResponse(user.getBufferedWriter(), "Spectator update...");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private synchronized void updateTeamScore(int teamID, long responseTime) {
        teamScores.put(teamID, teamScores.getOrDefault(teamID, 0) + 1);
        scoreList.add(new ScoreObject());
    }

    private void sendResponse(BufferedWriter bw, String response) throws IOException {
        bw.write(response);
        bw.write("\n");
        bw.flush();
    }

    public class NonBlockingTimer {
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        private ScheduledFuture<?> scheduledFuture;

        public void startTimer(Runnable task, long delayMillis) {
            CompletableFuture<Void> future = new CompletableFuture<>();

            scheduledFuture = scheduler.schedule(() -> {
                try {
                    task.run();
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }, delayMillis, TimeUnit.MILLISECONDS);

            future.thenRunAsync(() -> System.out.println("Task completed!"));
        }

        public void stopTimer() {
            if (scheduledFuture != null && !scheduledFuture.isDone()) {
                scheduledFuture.cancel(true);
                System.out.println("Timer stopped.");
            }
        }
    }
}
