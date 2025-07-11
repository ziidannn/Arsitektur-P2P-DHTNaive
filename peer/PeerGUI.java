package peer;

import common.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.Map;

public class PeerGUI extends JFrame {
    private JTextArea output;
    private int myId;
    private List<PeerInfo> peers;
    private DefaultTableModel tableModel;
    private RingPanel ringPanel;

    public PeerGUI(int myId, List<PeerInfo> peers) {
        this.myId = myId;
        this.peers = peers;

        setTitle("Peer " + myId);
        setSize(800, 550);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // 🔁 Panel visual ring
        ringPanel = new RingPanel(peers);
        add(ringPanel, BorderLayout.NORTH);

        output = new JTextArea();
        output.setEditable(false);
        add(new JScrollPane(output), BorderLayout.CENTER);

        String[] columns = {"File Name", "Hash", "Lokasi"};
        tableModel = new DefaultTableModel(columns, 0);
        JTable fileTable = new JTable(tableModel);
        add(new JScrollPane(fileTable), BorderLayout.EAST);

        JPanel panel = new JPanel();
        JButton uploadBtn = new JButton("Upload");
        JButton searchBtn = new JButton("Search");
        JButton downloadBtn = new JButton("Download");
        JButton refreshBtn = new JButton("Refresh File List");
        JButton deleteBtn = new JButton("Delete");
        JButton redrawBtn = new JButton("Redraw Ring");

        deleteBtn.addActionListener(this::handleDelete);
        refreshBtn.addActionListener(e -> refreshFileTable());
        uploadBtn.addActionListener(this::handleUpload);
        searchBtn.addActionListener(this::handleSearch);
        downloadBtn.addActionListener(this::handleDownload);
        redrawBtn.addActionListener(e -> ringPanel.repaint());

        panel.add(refreshBtn);
        panel.add(uploadBtn);
        panel.add(searchBtn);
        panel.add(downloadBtn);
        panel.add(deleteBtn);
        panel.add(redrawBtn);
        add(panel, BorderLayout.SOUTH);
    }

    private void handleUpload(ActionEvent e) {
        JFileChooser chooser = new JFileChooser();
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            String name = file.getName();
            int hash = Math.abs(name.hashCode()) % 32;
            PeerInfo target = findSuccessor(hash);
            output.append("Uploading " + name + " (hash=" + hash + ") to Node " + target.id + "\n");

            if (target.id == myId) {
                new File("shared").mkdirs();
                try {
                    java.nio.file.Files.copy(file.toPath(), new File("shared/" + name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    PeerMain.localFiles.put(name, new FileEntry(name));
                    PeerMain.hashToFile.put(hash, name);
                    refreshFileTable();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            } else {
                try (Socket socket = new Socket(target.ip, target.port);
                     ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

                    out.writeObject("UPLOAD");
                    out.writeObject(name);
                    out.writeObject(myId);
                    byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
                    out.writeObject(data);
                    output.append("File sent to Node " + target.id + "\n");

                } catch (Exception ex) {
                    ex.printStackTrace();
                    output.append("Failed to send file to Node " + target.id + "\n");
                }
            }
        }
    }

    private void handleSearch(ActionEvent e) {
        String input = JOptionPane.showInputDialog(this, "Enter file hash (0–31):");
        if (input == null || input.isEmpty()) return;
        int hash = Integer.parseInt(input);

        output.append("Searching file with hash = " + hash + "\n");
        forwardSearch(hash, myId); // mulai dari node ini
    }

    private void forwardSearch(int hash, int currentId) {
        PeerInfo current = null;
        for (PeerInfo peer : peers) {
            if (peer.id == currentId) {
                current = peer;
                break;
            }
        }

        if (current == null) return;

        try (Socket socket = new Socket(current.ip, current.port);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject("FORWARD_SEARCH");
            out.writeObject(hash);
            out.writeObject(myId);
            out.writeObject(0);
            out.writeObject("Node " + myId);

            String response = (String) in.readObject();
            String[] lines = response.split("\n");
            for (String line : lines) {
                output.append("[Search] " + line + "\n");
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void handleDownload(ActionEvent e) {
        String input = JOptionPane.showInputDialog(this, "Enter file hash (0–31) to download:");
        if (input == null || input.isEmpty()) return;

        int hash = Integer.parseInt(input);
        PeerInfo target = findSuccessor(hash);
        String filename = PeerMain.hashToFile.get(hash);

        if (filename == null) {
            output.append("File with hash " + hash + " not known in current node.\n");
            return;
        }

        output.append("Requesting download of \"" + filename + "\" from Node " + target.id + "\n");

        try (Socket socket = new Socket(target.ip, target.port);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject("DOWNLOAD");
            out.writeObject(filename);
            String status = (String) in.readObject();
            if ("OK".equals(status)) {
                byte[] data = (byte[]) in.readObject();
                File file = new File("downloads/" + filename);
                file.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                }

                output.append("Downloaded and saved to downloads/" + filename + "\n");
                PeerMain.localFiles.put(filename, new FileEntry(filename));
                int fileHash = Math.abs(filename.hashCode()) % 32;
                PeerMain.hashToFile.put(fileHash, filename);
                refreshFileTable();
            } else {
                output.append("File not found on server\n");
            }

        } catch (Exception ex) {
            ex.printStackTrace();
            output.append("Download failed\n");
        }
    }

    private void refreshFileTable() {
        tableModel.setRowCount(0);
        for (Map.Entry<String, FileEntry> entry : PeerMain.localFiles.entrySet()) {
            String filename = entry.getKey();
            int hash = entry.getValue().hash;
            tableModel.addRow(new Object[]{filename, hash, "Disimpan di sini"});
        }
    }

    private void handleDelete(ActionEvent e) {
        int selectedRow = -1;
        if (tableModel.getRowCount() > 0) {
            selectedRow = ((JTable) ((JScrollPane) getContentPane().getComponent(2)).getViewport().getView()).getSelectedRow();
        }

        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(this, "Pilih file yang ingin dihapus dari tabel.");
            return;
        }

        String filename = (String) tableModel.getValueAt(selectedRow, 0);
        int hash = (int) tableModel.getValueAt(selectedRow, 1);

        int confirm = JOptionPane.showConfirmDialog(this,
                "Apakah kamu yakin ingin menghapus file \"" + filename + "\"?",
                "Konfirmasi Hapus",
                JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            File file = new File("shared/" + filename);
            if (file.exists()) file.delete();

            PeerMain.localFiles.remove(filename);
            PeerMain.hashToFile.remove(hash);

            refreshFileTable();
            output.append("File \"" + filename + "\" berhasil dihapus dari node ini.\n");
        }
    }

    private PeerInfo findSuccessor(int hash) {
        for (PeerInfo peer : peers) {
            if (hash <= peer.id) return peer;
        }
        return peers.get(0); // wrap around
    }

    public void onFileReceived(String filename, int hash, String sender) {
        SwingUtilities.invokeLater(() -> {
            output.append("File masuk: " + filename + " (hash=" + hash + ") dari " + sender + "\n");
            refreshFileTable();
        });
    }

    // 🔁 PANEL GRAFIK RING LINGKARAN
    class RingPanel extends JPanel {
        private List<PeerInfo> peers;

        public RingPanel(List<PeerInfo> peers) {
            this.peers = peers;
            setPreferredSize(new Dimension(400, 300));
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (peers == null || peers.size() == 0) return;

            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            int radius = 100;

            int n = peers.size();
            Point[] positions = new Point[n];

            for (int i = 0; i < n; i++) {
                double angle = 2 * Math.PI * i / n;
                int x = (int) (cx + radius * Math.cos(angle));
                int y = (int) (cy + radius * Math.sin(angle));
                positions[i] = new Point(x, y);
            }

            // garis antar node
            g2d.setColor(Color.GRAY);
            for (int i = 0; i < n; i++) {
                Point p1 = positions[i];
                Point p2 = positions[(i + 1) % n];
                g2d.drawLine(p1.x, p1.y, p2.x, p2.y);
            }

            // gambar node
            for (int i = 0; i < n; i++) {
                Point p = positions[i];
                PeerInfo peer = peers.get(i);
                if (peer.id == myId) {
                    g2d.setColor(Color.ORANGE);
                } else {
                    g2d.setColor(Color.CYAN);
                }
                g2d.fillOval(p.x - 15, p.y - 15, 30, 30);
                g2d.setColor(Color.BLACK);
                g2d.drawOval(p.x - 15, p.y - 15, 30, 30);
                g2d.drawString(String.valueOf(peer.id), p.x - 8, p.y + 5);
            }
        }
    }
}