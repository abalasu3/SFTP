import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Sftpclient {

	static int latestAckReceived = 0;
	static volatile int beginWindow = 0, endWindow = 0, MSS, port;
	static InetAddress address = null;
	static byte[] sendingBuffer = null;
	static boolean allResourceFree = false;
	static DatagramSocket socket = null;
	static int highestSeqno = -1;
	static long startTime = -1;
	static long endTime = -1;
	final static Lock lock = new ReentrantLock();
	static int windowSize = -1;
	

	public static void freeResource() {
		allResourceFree = true;
	}

	public static void lockResource() {
		allResourceFree = false;
	}

	public class PacketTimer extends TimerTask {

		int seqno;

		public PacketTimer(int seqno) {
			this.seqno = seqno;
		}

		@Override
		public void run() {

			lock.lock();

			//System.out.println("t");

			try {
				ByteBuffer tempbuf = ByteBuffer.allocate(4);

				if (beginWindow < sendingBuffer.length) {
					tempbuf.put(sendingBuffer, beginWindow, 4);
					System.out.println("Timeout, Sequence Number: "+ (latestAckReceived+1));
				//	System.out.println("Timeout, Seqno : "
				//			+ (latestAckReceived+1) + "Ack obtained till :"
				//			+ latestAckReceived + "Begin Window: "
				//			+ beginWindow);
				}

				if (this.seqno < latestAckReceived
						&& highestSeqno != latestAckReceived) {
				//	System.out.println("Restarting timer : "
				//			+ tempbuf.getInt(0) + "last ack:"
				//			+ latestAckReceived);
					Timer timer = new Timer();
					timer.schedule(new Sftpclient().new PacketTimer(
							latestAckReceived + 1), 500);
					lock.unlock();
					// break;

				} else if (highestSeqno == latestAckReceived) {
					System.out.println("Last ack received : Exiting");
					Date d = new Date();
					endTime = d.getTime();
					long timeDiff = endTime - startTime;
					System.out.println("To Destination:"+ address + "it took " + timeDiff + " ms"+ " to deliver the file");
					System.exit(1);
				} else {
					//System.out.println("In send window, Begin :" + beginWindow
					//		+ ",End : " + endWindow);
					//sendWindow(sendingBuffer, beginWindow, endWindow, MSS,
					//		address, port);
					beginWindow = (latestAckReceived+1)*(MSS+4);
					if (sendingBuffer.length - endWindow < (MSS+4))
					{
						endWindow = sendingBuffer.length;
					}
					else
					{
					endWindow = beginWindow + (windowSize)*(MSS+4);
					}
					sendWindow(sendingBuffer, beginWindow, endWindow, MSS,
				address, port);
					lock.unlock();
					// break;
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(1);
			}

		}

	}

	public static void main(String[] args) {

		String hostName = args[0];
		port = Integer.parseInt(args[1]);
		String FileName = args[2];
		File sendingFile = new File(FileName);
		InputStream is = null;

		
		int fileSize = (int) sendingFile.length();
		int seqNo = 0;
		int ackNo = 0;
		

		/****** COnstants **********/
		windowSize = Integer.parseInt(args[3]);
		MSS = Integer.parseInt(args[4]);
		/*************************/

		int i = 0;
		int size;
		int sendingBufferSize = (int) (fileSize + (4 * Math
				.ceil((float) fileSize / MSS)));
		//System.out.println(sendingBufferSize);

		byte[] dataBuffer = new byte[MSS];
		byte[] ackBuffer = new byte[4];
		byte[] packetBuffer = null;

		ByteBuffer ackByteBuffer = ByteBuffer.allocate(4);
		ByteBuffer sendingByteBuffer = ByteBuffer.allocate(sendingBufferSize);
		dataBuffer = new byte[fileSize];

		DatagramSocket ackSocket = null;
		DatagramPacket packet = null;
		DatagramPacket ackPacket = new DatagramPacket(ackBuffer,
				ackBuffer.length);
		
		try {
			ackSocket = new DatagramSocket(7736);
			is = new FileInputStream(sendingFile);
			socket = new DatagramSocket();
			address = InetAddress.getByName(hostName);

			while ((is.read(dataBuffer)) > 0) {

				while (i < dataBuffer.length) {

					if (dataBuffer.length - i > MSS) {

						size = MSS;

						makePacket m = new makePacket(dataBuffer, i, size,
								seqNo);
						packetBuffer = m.getPacket();
						sendingByteBuffer.put(packetBuffer);
						i = i + MSS;
						seqNo = seqNo + 1;
					} else {
						size = dataBuffer.length - i;
						makePacket m = new makePacket(dataBuffer, i, size,
								seqNo);
						packetBuffer = m.getPacket();
						sendingByteBuffer.put(packetBuffer, 0,
								packetBuffer.length);

						i = i + MSS;
					}
				}
			}

			highestSeqno = seqNo;

			sendingBuffer = sendingByteBuffer.array();
			//System.out.println("Sending Buffer data size "
			//		+ sendingBuffer.length);

			endWindow = windowSize * (4 + MSS);

			Date d = new Date();
			startTime = d.getTime();

			lock.lock();
			sendWindow(sendingBuffer, beginWindow, endWindow, MSS, address,
					port);
			lock.unlock();

			while (latestAckReceived != highestSeqno) {

				try {

					// ackSocket.setSoTimeout(100);
					ackSocket.receive(ackPacket);
					lock.lock();
					ackBuffer = ackPacket.getData();
					ackByteBuffer.put(ackBuffer);
					ackByteBuffer.rewind();
					ackNo = ackByteBuffer.getInt(0);
					latestAckReceived = ackNo;
					lock.unlock();

				//	System.out.println("Ack Received: " + ackNo);

					if (sendingBuffer.length - endWindow > (MSS + 4)) {

						//System.out.println("1");
						lock.lock();
						packet = new DatagramPacket(sendingBuffer, endWindow,
								MSS + 4, address, port);
						

						socket.send(packet);
						beginWindow = beginWindow + MSS + 4;
						endWindow = endWindow + MSS + 4;
						ByteBuffer tempbuf = null;
						tempbuf = ByteBuffer.allocate(MSS + 4);
						tempbuf.put(sendingBuffer, packet.getOffset(), MSS + 4);
						//System.out.println("Packet Sent:" + tempbuf.getInt(0));
						lock.unlock();

					} else {

						if (endWindow == sendingBuffer.length) {
							lock.lock();
							beginWindow = beginWindow + MSS + 4;
					//		System.out.println("Begin Window: Incremented to "
					//				+ beginWindow);
							lock.unlock();

						} else {
					//		System.out.println("3");
							lock.lock();
							packet = new DatagramPacket(sendingBuffer,
									endWindow,
									sendingBuffer.length - endWindow, address,
									port);
							ByteBuffer tempbuf = null;
							tempbuf = ByteBuffer.allocate(MSS + 4);
							tempbuf.put(sendingBuffer, endWindow,
									sendingBuffer.length - endWindow);
						

							socket.send(packet);
							beginWindow = beginWindow + MSS + 4;
							endWindow = sendingBuffer.length;
							//System.out.println("Packet Sent:"
							//		+ tempbuf.getInt(0));
							lock.unlock();
						}
					}
				} catch (SocketTimeoutException e) {
					continue;
				}
			}

			System.out.println("Client done!");
			is.close();
			ackSocket.close();

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	static void sendWindow(byte[] sendingBuffer, int beginOffset,
			int endOffset, int MSS, InetAddress address, int port)
			throws IOException {
		int temp = beginOffset;
		DatagramPacket packet = null;
		Timer timer;
		ByteBuffer tempbuf = null;
		while (temp < endOffset) {

			if (endOffset - temp >= MSS + 4) {
				packet = new DatagramPacket(sendingBuffer, temp, MSS + 4,
						address, port);
				socket.send(packet);
				tempbuf = ByteBuffer.allocate(4);
				tempbuf.put(sendingBuffer, temp, 4);
			//	System.out.println("Packet Sent:" + tempbuf.getInt(0));
			} else {
				packet = new DatagramPacket(sendingBuffer, temp, endOffset
						- temp, address, port);
				socket.send(packet);
				tempbuf = ByteBuffer.allocate(4);
				tempbuf.put(sendingBuffer, temp, 4);
			//	System.out.println("Packet Sent:" + tempbuf.getInt(0));
			}

			temp += MSS + 4;
		}

		// Create timer
		timer = new Timer();
		timer.schedule(new Sftpclient().new PacketTimer(tempbuf.getInt(0)), 500);
	}
}