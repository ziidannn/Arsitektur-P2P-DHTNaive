
package peer;

import common.*;
import javax.swing.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class PeerMain {
    public static int myId;
    public static int myPort;
    public static String myIp;
    public static PeerGUI guiRef;

    public static List<PeerInfo> peers = new ArrayList<>();
    public static Map<String, FileEntry> localFiles = new HashMap<>();
    public static Map<Integer, String> hashToFile = new HashMap<>();

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: java peer.PeerMain <myId> <myIp> <myPort> <peerListFile>");
            System.exit(1);
        }

        myId = Integer.parseInt(args[0]);
        myIp = args[1];
        myPort = Integer.parseInt(args[2]);
        String configFile = args[3];

        loadPeerList(configFile);
        new Thread(() -> startServer()).start();

        guiRef = new PeerGUI(myId, peers);
        SwingUtilities.invokeLater(() -> guiRef.setVisible(true));  // cukup ini saja
    }

    private static void loadPeerList(String filename) {
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                int id = Integer.parseInt(parts[0]);
                String ip = parts[1];
                int port = Integer.parseInt(parts[2]);
                peers.add(new PeerInfo(id, ip, port));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(myPort)) {
            System.out.println("Peer " + myId + " listening on port " + myPort);
            while (true) {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClient(client)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void log(String msg) {
        try (FileWriter fw = new FileWriter("log.txt", true)) {
            fw.write("[" + new Date() + "] " + msg + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static PeerInfo getNextNode(int currentId) {
        List<PeerInfo> sorted = new ArrayList<>(peers);
        sorted.sort(Comparator.comparingInt(p -> p.id));

        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).id == currentId) {
                return sorted.get((i + 1) % sorted.size());
            }
        }
        return sorted.get(0); // fallback
    }

    private static void handleClient(Socket socket) {
        try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

            String command = (String) in.readObject();
            if (command.equals("SEARCH")) {
                String filename = (String) in.readObject();
                boolean found = localFiles.containsKey(filename);
                out.writeObject(found ? "FOUND" : "NOT_FOUND");
            } else if (command.equals("DOWNLOAD")) {
                String filename = (String) in.readObject();
                File file = new File("shared/" + filename);
                if (file.exists()) {
                    out.writeObject("OK");
                    byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
                    out.writeObject(data);
                } else {
                    out.writeObject("NOT_FOUND");
                }
            } else if (command.equals("UPLOAD")) {
                String filename = (String) in.readObject();
                int senderId = (int) in.readObject(); // ID pengirim
                byte[] data = (byte[]) in.readObject();

                File file = new File("shared/" + filename);
                file.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);

                    // Update state
                    localFiles.put(filename, new FileEntry(filename));
                    int hash = Math.abs(filename.hashCode()) % 32;
                    hashToFile.put(hash, filename);

                    // Update GUI
                    if (guiRef != null) {
                        guiRef.onFileReceived(filename, hash, "Node " + senderId);
                    }
                }
            } else if (command.equals("FORWARD_SEARCH")) {
                int hash = (int) in.readObject();
                int originId = (int) in.readObject();
                int hopCount = (int) in.readObject();
                String route = (String) in.readObject();

                hopCount++;
                route += " → Node " + myId;

                // Tentukan apakah saya adalah successor dari hash
                List<Integer> ids = new ArrayList<>();
                for (PeerInfo p : peers) ids.add(p.id);
                Collections.sort(ids);

                boolean isSuccessor = false;
                for (int id : ids) {
                    if (hash <= id) {
                        if (id == myId) isSuccessor = true;
                        break;
                    }
                }
                if (!isSuccessor && myId == ids.get(0) && hash > ids.get(ids.size() - 1)) {
                    isSuccessor = true;
                }

                if (isSuccessor) {
                    String filename = hashToFile.get(hash);
                    String msg;
                    if (filename != null) {
                        msg = "FOUND: " + filename + " at Node " + myId + "\n"
                            + "Hops: " + hopCount + "\n"
                            + "Route: " + route;
                    } else {
                        msg = "File with hash " + hash + " not found at Node " + myId + "\n"
                            + "Hops: " + hopCount + "\n"
                            + "Route: " + route;
                    }
                    out.writeObject(msg);
                } else {
                    // Teruskan ke node berikutnya
                    PeerInfo next = getNextNode(myId);
                    try (Socket forwardSocket = new Socket(next.ip, next.port);
                        ObjectOutputStream forwardOut = new ObjectOutputStream(forwardSocket.getOutputStream());
                        ObjectInputStream forwardIn = new ObjectInputStream(forwardSocket.getInputStream())) {

                        forwardOut.writeObject("FORWARD_SEARCH");
                        forwardOut.writeObject(hash);
                        forwardOut.writeObject(originId);
                        forwardOut.writeObject(hopCount);
                        forwardOut.writeObject(route);

                        String result = (String) forwardIn.readObject();
                        out.writeObject(result);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
