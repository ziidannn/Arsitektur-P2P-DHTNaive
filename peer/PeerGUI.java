package peer;

import common.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PeerGUI extends JFrame {
    private JTextArea output;
    private int myId;
    private List<PeerInfo> peers;
    private DefaultTableModel tableModel;
    private RingPanel ringPanel;
    private DefaultTableModel statusTableModel;
    private boolean firstStatusCheck = true;
    private Map<Integer, Boolean> lastNodeStatus = new HashMap<>();
    private JProgressBar progressBar;

    public PeerGUI(int myId, List<PeerInfo> peers) {
        this.myId = myId;
        this.peers = peers;

        setTitle("Peer " + myId);
        setSize(1000, 600);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // === LEFT PANEL: Ring (atas) + Tabel Status (bawah) ===
        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));

        // 🔁 RING PANEL
        ringPanel = new RingPanel(peers);
        ringPanel.setMinimumSize(new Dimension(300, 200));
        ringPanel.setPreferredSize(new Dimension(300, 200));
        ringPanel.setMaximumSize(new Dimension(Short.MAX_VALUE, 250));
        leftPanel.add(ringPanel);

        // 🔧 Spacer fleksibel
        leftPanel.add(Box.createVerticalStrut(10));

        // 📊 TABEL STATUS NODE
        String[] statusColumns = {"Node ID", "IP Address", "Status"};
        statusTableModel = new DefaultTableModel(statusColumns, 0);
        JTable statusTable = new JTable(statusTableModel);
        JScrollPane statusScroll = new JScrollPane(statusTable);

        // ❗ Batasi tinggi tabel agar tidak menekan ring
        statusScroll.setMaximumSize(new Dimension(Short.MAX_VALUE, 120));
        statusScroll.setPreferredSize(new Dimension(300, 100));
        leftPanel.add(statusScroll);

        add(leftPanel, BorderLayout.WEST);

        // === CENTER: Log Output ===
        output = new JTextArea();
        output.setEditable(false);
        JScrollPane outputScroll = new JScrollPane(output);
        add(outputScroll, BorderLayout.CENTER);

        // === SOUTH: Panel bawah tabel file dan tombol ===
        JPanel bottomPanel = new JPanel(new BorderLayout());

        // 📁 Tabel file
        String[] fileColumns = {"File Name", "Hash", "Lokasi"};
        tableModel = new DefaultTableModel(fileColumns, 0);
        JTable fileTable = new JTable(tableModel);
        JScrollPane fileScroll = new JScrollPane(fileTable);
        fileScroll.setPreferredSize(new Dimension(600, 120));
        bottomPanel.add(fileScroll, BorderLayout.CENTER);

        // 🔘 Tombol
        JPanel buttonPanel = new JPanel();
        JButton uploadBtn = new JButton("Upload");
        JButton searchBtn = new JButton("Search");
        // JButton downloadBtn = new JButton("Download");

        uploadBtn.addActionListener(this::handleUpload);
        searchBtn.addActionListener(this::handleSearch);
        // downloadBtn.addActionListener(this::handleDownload);

        buttonPanel.add(uploadBtn);
        buttonPanel.add(searchBtn);
        // buttonPanel.add(downloadBtn);
        bottomPanel.add(buttonPanel, BorderLayout.SOUTH);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        bottomPanel.add(progressBar, BorderLayout.NORTH);
        add(bottomPanel, BorderLayout.SOUTH);
        // ✅ Isi tabel status saat awal
        populateStatusTable();
        checkStatusUpdatePeriodically();  // mulai cek berkala
    }
    
    private void populateStatusTable() {
        statusTableModel.setRowCount(0); // clear existing
        for (PeerInfo peer : peers) {
            boolean alive = PeerMain.isNodeActive(peer);
            statusTableModel.addRow(new Object[]{
                peer.id,
                peer.ip,
                alive ? "🟢 Aktif" : "🔴 Tidak Aktif"
            });
        }
    }
    
    /**
     * @param e
     */
    private void handleUpload(ActionEvent e) {
        JFileChooser chooser = new JFileChooser();
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();

            UploadWorker worker = new UploadWorker(file);
            worker.execute();
        }
    }

    private void handleSearch(ActionEvent e) {
        String input = JOptionPane.showInputDialog(this, "Enter file hash (0–30):");
        if (input == null || input.isEmpty()) return;

        int hash = Integer.parseInt(input);
        output.append("🔍 Searching file with hash = " + hash + "\n");

        new Thread(() -> forwardSearch(hash, myId)).start(); // ⬅️ run in thread
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

    // private void handleDownload(ActionEvent e) {
    //     String input = JOptionPane.showInputDialog(this, "Enter file hash (0–31) to download:");
    //     if (input == null || input.isEmpty()) return;

    //     int hash = Integer.parseInt(input);
    //     PeerInfo target = findSuccessor(hash);
    //     String filename = PeerMain.hashToFile.get(hash);

    //     if (filename == null) {
    //         output.append("File with hash " + hash + " not known in current node.\n");
    //         return;
    //     }

    //     output.append("Requesting download of \"" + filename + "\" from Node " + target.id + "\n");

    //     try (Socket socket = new Socket(target.ip, target.port);
    //          ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
    //          ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

    //         out.writeObject("DOWNLOAD");
    //         out.writeObject(filename);
    //         String status = (String) in.readObject();
    //         if ("OK".equals(status)) {
    //             byte[] data = (byte[]) in.readObject();
    //             File file = new File("downloads/" + filename);
    //             file.getParentFile().mkdirs();
    //             try (FileOutputStream fos = new FileOutputStream(file)) {
    //                 fos.write(data);
    //             }

    //             output.append("Downloaded and saved to downloads/" + filename + "\n");
    //             PeerMain.localFiles.put(filename, new FileEntry(filename));
    //             int fileHash = Math.abs(filename.hashCode()) % 32;
    //             PeerMain.hashToFile.put(fileHash, filename);
    //             refreshFileTable();
    //         } else {
    //             output.append("File not found on server\n");
    //         }

    //     } catch (Exception ex) {
    //         ex.printStackTrace();
    //         output.append("Download failed\n");
    //     }
    // }

    private void refreshFileTable() {
        tableModel.setRowCount(0);
        for (Map.Entry<String, FileEntry> entry : PeerMain.localFiles.entrySet()) {
            String filename = entry.getKey();
            int hash = entry.getValue().hash;
            tableModel.addRow(new Object[]{filename, hash, "Disimpan di sini"});
        }
    }

    // private void handleDelete(ActionEvent e) {
    //     int selectedRow = -1;
    //     if (tableModel.getRowCount() > 0) {
    //         selectedRow = ((JTable) ((JScrollPane) getContentPane().getComponent(2)).getViewport().getView()).getSelectedRow();
    //     }
    //     if (selectedRow == -1) {
    //         JOptionPane.showMessageDialog(this, "Pilih file yang ingin dihapus dari tabel.");
    //         return;
    //     }
    //     String filename = (String) tableModel.getValueAt(selectedRow, 0);
    //     int hash = (int) tableModel.getValueAt(selectedRow, 1);
    //     int confirm = JOptionPane.showConfirmDialog(this,
    //             "Apakah kamu yakin ingin menghapus file \"" + filename + "\"?",
    //             "Konfirmasi Hapus",
    //             JOptionPane.YES_NO_OPTION);
    //     if (confirm == JOptionPane.YES_OPTION) {
    //         File file = new File("shared/" + filename);
    //         if (file.exists()) file.delete();
    //         PeerMain.localFiles.remove(filename);
    //         PeerMain.hashToFile.remove(hash);
    //         refreshFileTable();
    //         output.append("File \"" + filename + "\" berhasil dihapus dari node ini.\n");
    //     }
    // }

    private PeerInfo findSuccessor(int hash) {
        for (PeerInfo peer : peers) {
            if (hash <= peer.id && PeerMain.isNodeActive(peer)) {
                return peer; // ⬅️ Successor aktif
            }
        }

        // Wrap-around (loop ke awal ring)
        for (PeerInfo peer : peers) {
            if (PeerMain.isNodeActive(peer)) {
                return peer;
            }
        }

        // Fallback terakhir: diri sendiri
        return peers.stream()
                    .filter(p -> p.id == myId)
                    .findFirst()
                    .orElse(peers.get(0));
    }

    public void onFileReceived(String filename, int hash, String sender) {
        SwingUtilities.invokeLater(() -> {
            output.append("File masuk: " + filename + " (hash=" + hash + ") dari " + sender + "\n");
            refreshFileTable();
        });
    }

    private void populateStatusTableWithHighlight(Map<Integer, Boolean> changedStatus) {
        statusTableModel.setRowCount(0);

        for (PeerInfo peer : peers) {
            boolean active = PeerMain.isNodeActive(peer);

            Object[] row = {
                peer.id,
                peer.ip,
                active ? "🟢 Aktif" : "⚫ Tidak Aktif"
            };

            statusTableModel.addRow(row);
        }

        // 💡 Highlight perubahan
        JTable table = new JTable(statusTableModel) {
            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int col) {
                Component comp = super.prepareRenderer(renderer, row, col);
                int nodeId = (int) getValueAt(row, 0);
                if (changedStatus.containsKey(nodeId)) {
                    boolean newStatus = PeerMain.isNodeActive(peers.get(row));
                    comp.setBackground(newStatus ? new Color(204, 255, 204) : new Color(255, 204, 204)); // hijau/merah muda
                } else {
                    comp.setBackground(Color.white);
                }
                return comp;
            }
        };

        statusTableModel.fireTableDataChanged(); // render ulang
    }

    private void checkStatusUpdatePeriodically() {
        Timer timer = new Timer(5000, e -> {
            Map<Integer, Boolean> changed = new HashMap<>();

            for (PeerInfo peer : peers) {
                boolean current = PeerMain.isNodeActive(peer);
                boolean last = lastNodeStatus.getOrDefault(peer.id, !current); // anggap berubah di awal

                if (current != last) {
                    changed.put(peer.id, current);
                    lastNodeStatus.put(peer.id, current);

                    // ✅ Tampilkan log hanya jika bukan first run
                    if (!firstStatusCheck) {
                        SwingUtilities.invokeLater(() -> {
                            output.append("🔄 Status Node " + peer.id + " berubah menjadi " +
                                (current ? "🟢 AKTIF\n" : "⚫ TIDAK AKTIF\n"));
                        });
                    }
                } else {
                    lastNodeStatus.put(peer.id, current); // tetap update meskipun tidak berubah
                }
            }

            if (!changed.isEmpty()) {
                SwingUtilities.invokeLater(() -> populateStatusTableWithHighlight(changed));
            }

            firstStatusCheck = false; // ✅ hanya skip log pertama kali
        });

        timer.setRepeats(true);
        timer.start();
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

    class UploadWorker extends SwingWorker<Void, Integer> {
        private File file;

        public UploadWorker(File file) {
            this.file = file;
        }

        @Override
        protected Void doInBackground() {
            try {
                String name = file.getName();
                int originalHash = Math.abs(name.hashCode()) % 31;
                int hash = originalHash;
                int attempts = 0;

                // Cari hash yang belum dipakai
                while (PeerMain.hashToFile.containsKey(hash)) {
                    hash = (hash + 1) % 31;
                    attempts++;
                    if (attempts >= 31) {
                        SwingUtilities.invokeLater(() ->
                            output.append("❌ Semua hash penuh, tidak bisa menyimpan file!\n"));
                        return null;
                    }
                }

                final int finalHash = hash; // ✅ Final copy
                PeerInfo target = findSuccessor(finalHash);

                if (finalHash != originalHash) {
                    SwingUtilities.invokeLater(() ->
                        output.append("⚠️ Hash " + originalHash + " sudah dipakai, diganti ke hash " + finalHash + "\n"));
                }

                // ✅ Gunakan finalHash dalam UI update
                SwingUtilities.invokeLater(() -> {
                    progressBar.setIndeterminate(true);
                    progressBar.setValue(0);
                    progressBar.setString("📁 Mengunggah: " + name);
                    output.append("📦 Uploading: " + name + "\n");
                    output.append("🔍 Hash = " + finalHash + ", Target Node: " + target.id + "\n");
                });

                byte[] data = java.nio.file.Files.readAllBytes(file.toPath());

                if (target.id == myId) {
                    // ✅ Simpan lokal
                    new File("shared").mkdirs();
                    java.nio.file.Files.copy(file.toPath(),
                            new File("shared/" + name).toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    // ✅ Catat file lokal
                    PeerMain.localFiles.put(name, new FileEntry(name));
                    PeerMain.hashToFile.put(hash, name);

                    SwingUtilities.invokeLater(() -> {
                        refreshFileTable();
                        output.append("✅ File disimpan di node ini (Node " + myId + ")\n");
                    });

                } else {
                    // ✅ Kirim file ke node lain
                    try (Socket socket = new Socket(target.ip, target.port);
                        ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

                        out.writeObject("UPLOAD");         // perintah
                        out.writeObject(name);             // nama file
                        out.writeObject(myId);             // pengirim
                        out.writeObject(data);            // isi file (byte[])

                        SwingUtilities.invokeLater(() ->
                            output.append("✅ File terkirim ke Node " + target.id + "\n"));

                    } catch (IOException ex) {
                        ex.printStackTrace();
                        SwingUtilities.invokeLater(() ->
                            output.append("❌ Gagal upload ke Node " + target.id + ": " + ex.getMessage() + "\n"));
                    }
                }

            } catch (IOException ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() ->
                    output.append("❌ Terjadi error saat membaca file: " + ex.getMessage() + "\n"));
            }

            return null;
        }

        @Override
        protected void done() {
            progressBar.setIndeterminate(false);
            progressBar.setValue(100);
            progressBar.setString("✅ Selesai: " + file.getName());
        }
    }
}
