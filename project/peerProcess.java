import java.net.*;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Timer;
import java.util.logging.FileHandler;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class peerProcess {
    private Peer process;
    private int peer_ID;
    private int number_of_preferred_neighbors;
    private long unchocking_interval;
    private long optimistic_unchoking_interval;
    private String file_name;
    private int file_size;
    private int piece_size;
    private int sPort;
    private boolean hasFile;
    private ArrayList<Peer> existingPeers;
    private ArrayList<Peer> futurePeers;
    private HashMap<Integer, Handler> connectionArray;
    private ArrayList<Integer> preferredNeighbors;
    private int optUnchoked;
    private volatile Bitfield bitfield;
    private volatile byte[][] pieces;
    private volatile HashMap<Integer, Double> rates;

    private class Bitfield {
        byte[] bits;

        private HashMap<Integer, Integer> currentlyRequesting = new HashMap<>();

        public synchronized void update(int index) {
            bits[index] = (byte) 1;
        }

        public void announce(int index) {
            for (Handler thread : connectionArray.values()) {
                if (thread.clientBitfield.bits[index] == 0) {
                    thread.sendHave(index);
                }
            }
        }

        public void announceCompletion() {
            for (Handler thread : connectionArray.values()) {
                thread.sendBitfield();
            }
        }

        public boolean isComplete() {
            for (byte hasPiece : bits) {
                if (hasPiece == 0) {
                    return false;
                }
            }
            
            return true;
        }

        public synchronized void newRequest(int index, int id) {
            currentlyRequesting.put(id, index);
        }

        public synchronized void removeRequest(int index) {
            ArrayList<Integer> removal = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : currentlyRequesting.entrySet()) {
                if (entry.getValue() == index) {
                    removal.add(entry.getKey());
                }
            }
            for (Integer key : removal) {
                currentlyRequesting.remove(key);
            }
        }

    }

    public class Peer {
        int peerID;
        String address;
        int port;
        boolean hasFile;

        public Peer(int id, String address, int port, boolean hasFile) {
            this.peerID = id;
            this.address = address;
            this.port = port;
            this.hasFile = hasFile;
        }
    }

    public void optimisticallyUnchokeNeighbor() {
        // get list of peers
        ArrayList<Handler> possiblePeers = new ArrayList<>();
        Object[] temp = connectionArray.values().toArray();
        for (Object obj : temp) {
            possiblePeers.add((Handler) obj);
        }

        // then, get only peers that are interested in what we have, and they are choked
        possiblePeers.removeIf(peer -> !peer.client_interested || !peer.client_choked);

        // pick a random one out of those
        if (possiblePeers.size() > 0) {
            int random_index = new Random().nextInt(possiblePeers.size());

            Handler optUnchoked = possiblePeers.get(random_index);

            optUnchoked.log_optimistic_unchoke();
            optUnchoked.sendUnchoke();
        }
    }


    // is responsible for setting class variables using the peer_ID, the Common.cfg, and PeerInfo.cfg
    public peerProcess(int p_ID) {
        this.peer_ID = p_ID;

        // reading Common.cfg
        Properties common = new Properties();
        try (FileInputStream fis = new FileInputStream("Common.cfg")) {
            common.load(fis);
            fis.close();
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }

        this.number_of_preferred_neighbors = Integer.parseInt(common.getProperty("NumberOfPreferredNeighbors"));
        this.unchocking_interval = Integer.parseInt(common.getProperty("UnchokingInterval"));
        this.optimistic_unchoking_interval = Integer.parseInt(common.getProperty("OptimisticUnchokingInterval"));

        this.file_name = common.getProperty("FileName");
        this.file_size = Integer.parseInt(common.getProperty("FileSize"));
        this.piece_size = Integer.parseInt(common.getProperty("PieceSize"));
        this.preferredNeighbors = new ArrayList<>();

        // setting bitfield
        int bitfield_size = calculate_bitfield_size(this.file_size, this.piece_size);
        this.bitfield = new Bitfield();
        this.bitfield.bits = new byte[bitfield_size];
        this.pieces = new byte[bitfield_size][];

        // reading PeerInfo.cfg
        this.existingPeers = new ArrayList<>();
        this.futurePeers = new ArrayList<>();
        this.connectionArray = new HashMap<>();
        this.rates = new HashMap<Integer, Double>();
        File file = new File("PeerInfo.cfg");
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            for (String line; (line = br.readLine()) != null; ) {

                String[] split = line.split(" ");

                int new_id = Integer.parseInt(split[0]);
                String new_address = split[1];
                int new_port = Integer.parseInt(split[2]);
                boolean hasFile = Integer.parseInt(split[3]) == 1;

                if (new_id != this.peer_ID) {
                    this.existingPeers.add(new Peer(new_id, new_address, new_port, hasFile));
                    this.rates.put(new_id, 0.0);
                } else {
                    process = new Peer(this.peer_ID, new_address, new_port, hasFile);
                    this.sPort = new_port;
                    this.hasFile = hasFile;
                    break;
                }

            }
            // keep reading after existingPeers stopped
            for (String line; (line = br.readLine()) != null; ) {
                String[] split = line.split(" ");

                int new_id = Integer.parseInt(split[0]);
                String new_address = split[1];
                int new_port = Integer.parseInt(split[2]);
                boolean hasFile = Integer.parseInt(split[3]) == 1;

                this.futurePeers.add(new Peer(new_id, new_address, new_port, hasFile));
                this.rates.put(new_id, 0.0);
            }
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
        File dir = new File("./" + String.valueOf(peer_ID));
        if (!dir.exists())
        {
            dir.mkdirs();
        }

        // setting bitfield default value based on having the file
        if (this.hasFile) {
            Arrays.fill(bitfield.bits, (byte) 1);
            Path path = Paths.get("./" + this.peer_ID + "/" + file_name);
            byte[] fileBytes = new byte[0];
            
            try {
                fileBytes = Files.readAllBytes(path);
            } catch (IOException e) {
                e.printStackTrace();
            }
            int numBytes = piece_size;
            for (int i = 0; i < bitfield_size; i++) {
                if (i == bitfield_size - 1)
                {
                    numBytes = file_size - (i * piece_size);
                }
                byte[] temp = new byte[numBytes];
                if (numBytes - 1 >= 0) {
                    System.arraycopy(fileBytes, (i * piece_size), temp, 0, numBytes);
                }
                pieces[i] = temp;
            }

        } else {
            Arrays.fill(bitfield.bits, (byte) 0);
            bitfield.bits[0] = (byte) 0;
        }

        // function to connect the process to the others
        connectToOtherProcesses();

        Timer time = new Timer(); // Instantiate Timer Object
        ScheduledChooseNeighbors choosePreferred = new ScheduledChooseNeighbors();
        ScheduledOptimisticUnchoke optimisticUnchoke = new ScheduledOptimisticUnchoke();

        time.schedule(choosePreferred, 1, ((long) 1000 * unchocking_interval));
        time.schedule(optimisticUnchoke, 1, ((long) 1000 * optimistic_unchoking_interval));

        boolean done = false;
        while (!done) {
            done = everyoneComplete();
        }

        int file_length = 0;

        for (byte[] piece : this.pieces) {
            file_length += piece.length;
        }


        byte[] file_data = new byte[file_length];
        int amount_read = 0;

        for (byte[] piece : this.pieces) {
            System.arraycopy(piece, 0, file_data, amount_read, piece.length);
            amount_read += piece.length;
        }

        if (!process.hasFile) {
            try {
                OutputStream os = new FileOutputStream("./" + this.peer_ID + "/" + file_name);
                os.write(file_data);
                os.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        

        choosePreferred.cancel();
        optimisticUnchoke.cancel();

        for (Handler thread : connectionArray.values()) {
            thread.client_has_file = true;
            thread.client_interested = false;
            thread.host_has_file = true;
            thread.host_interested = false;

            thread.done = true;
        }

        System.exit(0);
    }

    public void connectToOtherProcesses() {
        try {
            // request to connect to existing peer processes
            if (this.existingPeers.size() > 0) {
                for (Peer current : this.existingPeers) {
                    // to avoid a port connecting to itself
                    if (current.peerID == this.peer_ID) {
                        continue;
                    }

                    // create the socket to connect to the others and start the process
                    Socket requestSocket = new Socket(current.address, current.port);

                    // initialize client bitfield
                    Bitfield client_bitfield = new Bitfield();
                    client_bitfield.bits = new byte[bitfield.bits.length];

                    // create handler for connection host-client
                    Handler handler = new Handler(requestSocket, current, process, this.bitfield, client_bitfield, this.pieces, this.rates);
                    handler.sendHandshake();
                    handler.start();

                    this.connectionArray.put(current.peerID, handler);
                }
            }

            if (this.futurePeers.size() > 0) {
                // make this process listen for those that have yet to start
                ServerSocket listener = new ServerSocket(this.sPort);

                for (Peer future : this.futurePeers) {
                    // initialize client bitfield
                    Bitfield client_bitfield = new Bitfield();
                    client_bitfield.bits = new byte[bitfield.bits.length];

                    // create handler for connection host-client
                    Handler handler = new Handler(listener.accept(), future, process, this.bitfield, client_bitfield, this.pieces, this.rates);
                    handler.listenHandshake();
                    handler.start();

                    this.connectionArray.put(future.peerID, handler);
                }
            }
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    public void choosePreferredNeighbors() {
        ArrayList<Integer> preferred = new ArrayList<>();
        for (int i = 0; i < this.number_of_preferred_neighbors; i++) {
            HashMap<Integer, Double> temp = new HashMap<>();
            double fastest = 0;
            if (Collections.max(this.rates.values()) == 0 || this.connectionArray.get(this.rates.keySet().toArray()[0]).host_has_file) {
                Random rand = new Random();
                ArrayList<Integer> arrRates = new ArrayList<>();
                for (int key : this.rates.keySet()) {
                    if (this.connectionArray.get(key).client_interested) {
                        arrRates.add(key);
                    }
                }
                int index = 0;
                do {
                    if (arrRates.size() == 0)
                    {
                        break;
                    }
                    index = rand.nextInt(arrRates.size());
                } while (preferred.contains(arrRates.get(index)) && arrRates.size() > 0);
                if (index < arrRates.size() && !preferred.contains(arrRates.get(index))) {
                    preferred.add(arrRates.get(index));
                }
            } else {
                for (Map.Entry<Integer, Double> entry : this.rates.entrySet()) {
                    if (preferred.contains(entry.getKey())) {
                        continue;
                    }
                    if (temp.isEmpty() && this.connectionArray.get(entry.getKey()).client_interested) {
                        temp.put(entry.getKey(), entry.getValue());
                    } else if (temp.isEmpty()) {
                        continue;
                    } else if (Collections.min(temp.values()) == 0 || Collections.min(temp.values()) > entry.getValue() && this.connectionArray.get(entry.getKey()).client_interested) {
                        temp.clear();
                        temp.put(entry.getKey(), entry.getValue());
                    } else if (Collections.min(temp.values()) == entry.getValue() && this.connectionArray.get(entry.getKey()).client_interested) {
                        temp.put(entry.getKey(), entry.getValue());
                    } else {
                        continue;
                    }
                }
            }
            Random rand = new Random();
            Object[] arrRates = temp.keySet().toArray();
            if (temp.size() > 0) {
                int index = rand.nextInt(temp.size());
                preferred.add((Integer) arrRates[index]);
            }
        }
        for (int i = 0; i < this.number_of_preferred_neighbors; i++) {
            if (preferred.size() > i && this.preferredNeighbors.contains(preferred.get(i))) {
                continue;
            } else if (this.preferredNeighbors.size() > i && !preferred.contains(this.preferredNeighbors.get(i))) {
                this.connectionArray.get(this.preferredNeighbors.get(i)).sendChoke();
            } else if (preferred.size() > i && !this.preferredNeighbors.contains(preferred.get(i))) {
                this.connectionArray.get(preferred.get(i)).sendUnchoke();
                this.connectionArray.get(preferred.get(i)).log_preferred_peer();
            }
        }
        this.preferredNeighbors = preferred;
    }

    public int calculate_bitfield_size(int file_size, int piece_size) {
        return (int) Math.ceil(((float) file_size) / ((float) piece_size));
    }

    private static class Handler extends Thread {
        private Socket connection;
        //stream read from the socket
        private DataInputStream in;
        //stream write to the socket
        private DataOutputStream out;

        //The id of the connected peer
        private int hostId;
        // The id of the current peer	
        private int clientId;

        // logging stream
        private Logger logger = Logger.getLogger("MyLog");

        // bitfield of connected peer
        private Bitfield hostBitfield;

        // bitfield of current peer
        private Bitfield clientBitfield;

        // array containing file pieces
        private byte[][] pieces;

        private HashMap<Integer, Double> rates;

        //bool for keeping track if interested
        boolean host_interested = false;
        boolean client_interested = false;

        // to keep track if client has us choked
        boolean host_choked = false;
        boolean client_choked = false;

        // keep track of file progress
        boolean host_has_file = false;
        boolean client_has_file = false;

        boolean done = false;

        public void run() {
            log("Peer " + hostId + " can start sending packets to " + clientId);

            sendBitfield();

            while (true) {
                try {
                    // get message length
                    byte[] msgLen = new byte[4];

                    //noinspection ResultOfMethodCallIgnored
                    int length_read = in.read(msgLen);

                    if(length_read == 0){
                        continue;
                    }

                    byte msg_type = in.readByte();
                    boolean read_payload = has_payload(msg_type);

                    // get message payload if necessary
                    if (read_payload) {
                        receiveMessageWithPayload(msg_type, getNumber(msgLen) - 1);
                    } else {
                        receiveMessage(msg_type);
                    }

                    if (host_has_file && client_has_file) {
                        host_interested = false;
                        client_interested = false;
                    }

                    if(done){
                        break;
                    }
                } catch (IOException e) {
                    break;
                }
            }

            log("Peer " + hostId + " has finished communication with " + clientId);
            try
            {
                connection.close();
            }
            catch (IOException e) {
                return;
            }
            Thread.currentThread().interrupt();
        }

        private void receiveMessageWithPayload(byte type, int msgLen) {
            // when reading the payload, we will need a loop to receive all the packets.

            int curr_read = 0;
            byte[] payload = new byte[msgLen];
            int current_package_length;

            double start = System.nanoTime();
            while (curr_read < msgLen) {
                byte[] data = new byte[msgLen - curr_read];

                try {
                    current_package_length = in.read(data, 0, data.length);
                    System.arraycopy(data, 0, payload, curr_read, current_package_length);
                    curr_read += current_package_length;
                } catch (IOException e) {
                    break;
                }
            }
            double end = System.nanoTime();

            if (type == BITFIELD) {
                log("Peer " + hostId + " received BITFIELD from Peer " + clientId);
                clientBitfield.bits = payload;

                client_has_file = clientBitfield.isComplete();

                if (client_has_file) {
                    client_interested = false;
                }

                // after someone sends bitfield, see if we are interested in what they have (compare bitfields)
                // then update interested field
                host_interested = hasMissingPieces(clientBitfield.bits);
                // then send interested/not interested message
                if (host_interested) {
                    sendInterested();
                } else {
                    sendNotInterested();
                }

                //
            } else if (type == HAVE) {
                byte[] indexBytes = (Arrays.copyOfRange(payload, 0, 4));
                int index = getNumber(indexBytes);

                log("Peer " + hostId + " received HAVE from Peer " + clientId + " for piece " + index);

                clientBitfield.update(index);

                client_has_file = clientBitfield.isComplete();

                if (client_has_file) {
                    client_interested = false;
                }

                boolean temp = hasMissingPieces(clientBitfield.bits);

                if (temp && !host_interested) {
                    host_interested = temp;
                    sendInterested();
                } else if (!temp && host_interested) {
                    host_interested = temp;
                    sendNotInterested();
                }

            } else if (type == REQUEST) {
                int index = getNumber(payload);

                log("Peer " + hostId + " received REQUEST from Peer " + clientId + " for piece " + index);

                // first, check if host has the piece
                if ((hostBitfield.bits[index] == 0)) {
                    return;
                }

                // second, check if host has client choked
                if (!client_choked) {
                    sendPiece(index);
                }
            } else if (type == PIECE) {
                byte[] indexBytes = (Arrays.copyOfRange(payload, 0, 4));
                int index = getNumber(indexBytes);

                pieces[index] = (Arrays.copyOfRange(payload, 4, payload.length));

                double rate = (end - start);

                this.rates.put(this.clientId, rate);

                hostBitfield.update(index);
                hostBitfield.removeRequest(index);

                int count = 0;
                for (int i = 0; i < hostBitfield.bits.length; i++) {
                    if (hostBitfield.bits[i] == (byte) 1) {
                        count++;
                    }
                }

                double percent = (double) count / hostBitfield.bits.length * 100.0;

                String progress = String.format("Piece count: %d. (%.2f)%%", count, percent);

                log("Peer " + hostId + " has downloaded the piece " + index + " from Peer " + clientId + ". " + progress);

                hostBitfield.announce(index);

                // check if file bitfield is full and then write file,
                // update hasFile

                host_has_file = hostBitfield.isComplete();

                if (host_has_file) {
                    hostBitfield.announceCompletion();
                    log(String.format("Peer %d now has the entire file.", hostId));
                }

                boolean temp = hasMissingPieces(clientBitfield.bits);

                // ask for another piece

                if (temp && host_interested) {
                    int next_piece = getRandomMissingPiece();
                    if (next_piece >= 0) {
                        hostBitfield.newRequest(next_piece, this.clientId);
                        sendRequest(next_piece);
                    } else {
                        return;
                    }
                } else if (temp && !host_interested) {
                    sendInterested();
                    host_interested = temp;
                } else if (!temp && host_interested) {
                    sendNotInterested();
                    host_interested = temp;
                }
            }
        }

        private void receiveMessage(byte type) {
            // make the modifications inside state variables

            if (type == CHOKE) {
                host_choked = true;
                log("Peer " + hostId + " received choke message by Peer " + clientId);
                hostBitfield.currentlyRequesting.remove(this.clientId);
            } else if (type == UNCHOKE) {
                host_choked = false;
                log("Peer " + hostId + " received unchoke message by Peer " + clientId);

                // see if they have a piece that interests us
                host_interested = hasMissingPieces(clientBitfield.bits);

                // ask for another piece
                if (host_interested) {
                    int next_piece = getRandomMissingPiece();
                    if (next_piece >= 0) {
                        hostBitfield.newRequest(this.clientId, next_piece);
                        sendRequest(next_piece);
                    }
                }
            } else if (type == INTERESTED) {
                client_interested = true;
                log("Peer " + hostId + " received interest message from Peer " + clientId);

            } else if (type == NOT_INTERESTED) {
                client_interested = false;
                log("Peer " + hostId + " received not interested message from Peer " + clientId);
            }
        }

        public Handler(Socket connection, Peer client, Peer host, Bitfield host_bitfield, Bitfield client_bitfield, byte[][] pieces, HashMap<Integer, Double> rates) {
            //setup
            this.connection = connection;

            this.pieces = pieces;

            this.hostId = host.peerID;
            this.hostBitfield = host_bitfield;
            this.host_has_file = host.hasFile;

            this.clientId = client.peerID;
            this.client_has_file = client.hasFile;
            this.clientBitfield = client_bitfield;

            this.rates = rates;

            if (client_has_file) {
                Arrays.fill(clientBitfield.bits, (byte) 1);

                if (!host_has_file) {
                    host_interested = true;
                }
            } else {
                Arrays.fill(clientBitfield.bits, (byte) 0);
            }

            // logger
            FileHandler fh;
            InputStream loggerProps = peerProcess.class.getResourceAsStream("/logger.properties");

            try {
                // This block configures the logger with handler and formatter  
                LogManager.getLogManager().readConfiguration(loggerProps);
                fh = new FileHandler("./log_peer_" + this.hostId + ".log", true);
                logger.addHandler(fh);
                SimpleFormatter formatter = new SimpleFormatter();
                fh.setFormatter(formatter);
                // the following statement is used to log any messages    

            } catch (SecurityException | IOException ex) {
                ex.printStackTrace();
            }

            try {
                this.out = new DataOutputStream(this.connection.getOutputStream());
                this.out.flush();
                this.in = new DataInputStream(this.connection.getInputStream());
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        }

        public void sendHandshake() {
            try {
                log("Initiating existing peer handshake: " + clientId);

                ByteArrayOutputStream send = new ByteArrayOutputStream(32);

                byte[] header = "P2PFILESHARINGPROJ".getBytes();
                byte[] zeros = new byte[10];
                byte[] p_id = ByteBuffer.allocate(4).putInt(this.hostId).array();

                send.write(header, 0, 18);
                send.write(zeros, 0, 10);
                send.write(p_id, 0, 4);

                log("Peer " + this.hostId + " makes a connection to " + this.clientId + ".");

                out.write(send.toByteArray());

                returnListen();
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        }

        public void listenHandshake() {
            try {
                log("Expected future peer handshake: " + clientId);

                byte[] receive = new byte[32];
                int bytesNum = in.read(receive);

                int from = ByteBuffer.wrap(Arrays.copyOfRange(receive, 28, 32)).getInt();

                if (from != this.clientId) {
                    log("Incorrect peerID received... <" + from + ">");
                    Thread.currentThread().interrupt();
                }

                log("Peer " + this.hostId + " received connection request from Peer " + this.clientId + ".");
                returnHandshake();

            } catch (IOException ioException) {
                Thread.currentThread().interrupt();
            }
        }

        public void returnHandshake() {
            try {
                byte[] header = "P2PFILESHARINGPROJ".getBytes();
                byte[] zeros = new byte[10];
                byte[] p_id = ByteBuffer.allocate(4).putInt(this.hostId).array();

                ByteArrayOutputStream send = new ByteArrayOutputStream(32);

                send.write(header, 0, 18);
                send.write(zeros, 0, 10);
                send.write(p_id, 0, 4);

                out.write(send.toByteArray());

                log("Peer " + this.hostId + " returned handshake to " + this.clientId + ".");
            } catch (IOException ioException) {
                Thread.currentThread().interrupt();
            }
        }

        public void returnListen() {
            try {
                byte[] receive = new byte[32];
                int bytesNum = in.read(receive);

                int from = ByteBuffer.wrap(Arrays.copyOfRange(receive, 28, 32)).getInt();

                if (from != this.clientId) {
                    log("Incorrect peerID received... <" + from + ">");
                    Thread.currentThread().interrupt();
                }

                log("Peer " + this.hostId + " successfully established connection to " + this.clientId + ".");
            } catch (IOException ioException) {
                Thread.currentThread().interrupt();
            }
        }

        public void sendBitfield() {
            try {
                // please test this, not sure if I assigned the parameters correctly

                byte[] length = ByteBuffer.allocate(4).putInt(hostBitfield.bits.length + 1).array();
                byte[] type = new byte[]{BITFIELD};
                byte[] payload = hostBitfield.bits;

                ByteArrayOutputStream send = new ByteArrayOutputStream(hostBitfield.bits.length + 5);
                send.write(length, 0, 4);
                send.write(type, 0, 1);
                send.write(payload, 0, hostBitfield.bits.length);

                out.write(send.toByteArray());

                log("Peer " + hostId + " sent BITFIELD message to Peer " + clientId);
            } catch (IOException ioException) {
                Thread.currentThread().interrupt();
            }
        }

        public void sendPiece(int index) {
            try {
                byte[] length = ByteBuffer.allocate(4).putInt(5 + pieces[index].length).array();
                byte[] type = new byte[]{PIECE};
                byte[] indexByte = ByteBuffer.allocate(4).putInt(index).array();
                byte[] payload = pieces[index];

                ByteArrayOutputStream send = new ByteArrayOutputStream(9+pieces[index].length);
                send.write(length, 0, 4);
                send.write(type, 0, 1);
                send.write(indexByte, 0, 4);
                send.write(payload, 0, pieces[index].length);
 
                out.write(send.toByteArray());

                log("Peer " + hostId + " sent PIECE response to Peer " + clientId + " for piece " + index);
            } catch (IOException ioException) {
                Thread.currentThread().interrupt();
            }
        }

        public void sendHave(int index) {
            try {
                ByteArrayOutputStream send = new ByteArrayOutputStream(9);
                byte[] length = ByteBuffer.allocate(4).putInt(5).array();
                byte[] type = new byte[]{HAVE};
                byte[] payload = ByteBuffer.allocate(4).putInt(index).array();

                send.write(length, 0, 4);
                send.write(type, 0, 1);
                send.write(payload, 0, 4);

                out.write(send.toByteArray());

                log("Peer " + hostId + " sent HAVE message to Peer " + clientId + " for piece " + index);
            } catch (IOException ioException) {
                Thread.currentThread().interrupt();
            }
        }

        public void sendRequest(int index) {
            try {
                ByteArrayOutputStream send = new ByteArrayOutputStream(9);
                byte[] length = ByteBuffer.allocate(4).putInt(5).array();
                byte[] type = new byte[]{REQUEST};
                byte[] payload = ByteBuffer.allocate(4).putInt(index).array();

                send.write(length, 0, 4);
                send.write(type, 0, 1);
                send.write(payload, 0, 4);

                out.write(send.toByteArray());

                log("Peer " + hostId + " sent Request message to Peer " + clientId + " for piece " + index);
            } catch (IOException ioException) {
                Thread.currentThread().interrupt();
            }
        }

        public void sendInterested() {
            try {
                ByteArrayOutputStream send = new ByteArrayOutputStream(5);
                byte[] length = ByteBuffer.allocate(4).putInt(1).array();
                byte[] type = new byte[]{INTERESTED};

                send.write(length, 0, 4);
                send.write(type, 0, 1);

                host_interested = true;

                out.write(send.toByteArray());

                log("Peer " + hostId + " sent Interest message to Peer " + clientId);
            } catch (IOException ioException) {
                Thread.currentThread().interrupt();
            }
        }

        public void sendNotInterested() {
            try {
                ByteArrayOutputStream send = new ByteArrayOutputStream(5);
                byte[] length = ByteBuffer.allocate(4).putInt(1).array();
                byte[] type = new byte[]{NOT_INTERESTED};

                send.write(length, 0, 4);
                send.write(type, 0, 1);

                host_interested = false;

                out.write(send.toByteArray());

                log("Peer " + hostId + " sent Not Interested message to Peer " + clientId);
            } catch (IOException ioException) {
                Thread.currentThread().interrupt();
            }
        }

        public void sendChoke() {
            try {
                ByteArrayOutputStream send = new ByteArrayOutputStream(5);
                byte[] length = ByteBuffer.allocate(4).putInt(1).array();
                byte[] type = new byte[]{CHOKE};

                send.write(length, 0, 4);
                send.write(type, 0, 1);

                client_choked = true;

                out.write(send.toByteArray());

                log("Peer " + hostId + " sent Choke message to Peer " + clientId);
            } catch (IOException ioException) {
                Thread.currentThread().interrupt();
            }
        }

        public void sendUnchoke() {
            try {
                ByteArrayOutputStream send = new ByteArrayOutputStream(5);
                byte[] length = ByteBuffer.allocate(4).putInt(1).array();
                byte[] type = new byte[]{UNCHOKE};

                send.write(length, 0, 4);
                send.write(type, 0, 1);

                client_choked = false;

                out.write(send.toByteArray());

                log("Peer " + hostId + " sent Unchoke message to Peer " + clientId);
            } catch (IOException ioException) {
                Thread.currentThread().interrupt();
            }
        }

        public void log_preferred_peer() {
            log("Peer " + hostId + " has chosen Peer " + clientId + " as a preferred neighbor.");
        }

        public void log_optimistic_unchoke() {
            log("Peer " + hostId + " optimistically Unchoked Peer " + clientId);
        }

        private int getNumber(byte[] payload) {
            return ByteBuffer.wrap(payload).getInt();
        }

        private boolean hasMissingPieces(byte[] clientBitfield) {
            for (int i = 0; i < clientBitfield.length; i++) {
                if (clientBitfield[i] == (byte) 1 && hostBitfield.bits[i] == (byte) 0) {
                    return true;
                }
            }
            return false;
        }

        public final byte CHOKE = (byte) 0;
        public final byte UNCHOKE = (byte) 1;
        public final byte INTERESTED = (byte) 2;
        public final byte NOT_INTERESTED = (byte) 3;
        public final byte HAVE = (byte) 4;
        public final byte BITFIELD = (byte) 5;
        public final byte REQUEST = (byte) 6;
        public final byte PIECE = (byte) 7;

        public boolean has_payload(byte message_type) {
            switch (message_type) {
                case CHOKE:
                    return false;
                case UNCHOKE:
                    return false;
                case INTERESTED:
                    return false;
                case NOT_INTERESTED:
                    return false;
                case HAVE:
                    return true;
                case BITFIELD:
                    return true;
                case REQUEST:
                    return true;
                case PIECE:
                    return true;

            }
            return false;
        }

        private void log(String toBeLogged) {
            System.out.println(toBeLogged); // good for testing purposes
            System.out.flush();
            logger.info(toBeLogged);
        }

        public int getRandomMissingPiece() {

            HashMap<Integer, Byte> possiblePieces = new HashMap<>();

            for (int i = 0; i < clientBitfield.bits.length; i++) {
                if (clientBitfield.bits[i] == (byte) 1 && hostBitfield.bits[i] == (byte) 0) {
                    possiblePieces.put(i, clientBitfield.bits[i]);
                }
            }
            if (possiblePieces.size() > 0) {
                int member = new Random().nextInt(possiblePieces.size());
                Object[] keyArray = possiblePieces.keySet().toArray();

                return (int) keyArray[member];
            } else {
                return -1;
            }
        }
    }

    public boolean everyoneComplete() {
        if (!this.bitfield.isComplete()) {
            return false;
        }

        for (Handler peer : connectionArray.values()) {
            if (!peer.clientBitfield.isComplete()) {
                return false;
            }
        }
        return true;
    }

    public class ScheduledChooseNeighbors extends TimerTask {

        public void run() {
            choosePreferredNeighbors();
        }
    }

    public class ScheduledOptimisticUnchoke extends TimerTask {

        public void run() {
            optimisticallyUnchokeNeighbor();
        }
    }

    public static void main(String[] args) throws Exception {
        // check there is a command line argument
        if (args.length == 0) {
            throw new Exception("Error: Need To Give a Peer ID");
        }

        // check the peer_ID is an integer
        int peer_ID_input;
        try {
            peer_ID_input = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            throw new Exception("Error: Peer ID Needs To Be An Integer");
        }

        // deletes any previous log files for this peer
        File folder = new File(".");
        for (File f : folder.listFiles()) {
            if (f.getName().endsWith(peer_ID_input + ".log")) {
                f.delete();
            }
        }
        // create the peer_process
        peerProcess peer_process = new peerProcess(peer_ID_input);
    }
}
