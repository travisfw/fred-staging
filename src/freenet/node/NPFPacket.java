/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Comparator;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;

import freenet.support.Logger;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger.LogLevel;

class NPFPacket {
	private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	private int sequenceNumber;
	private final SortedSet<Integer> acks = new TreeSet<Integer>();
	private final LinkedList<MessageFragment> fragments = new LinkedList<MessageFragment>();
	private boolean error;
	private int length = 5; //Sequence number (4), numAcks(1)

	public static NPFPacket create(byte[] plaintext) {
		NPFPacket packet = new NPFPacket();
		int offset = 0;

		if(plaintext.length < (offset + 5)) { //Sequence number + the number of acks
			packet.error = true;
			return packet;
		}

		packet.sequenceNumber = ((plaintext[offset] & 0xFF) << 24)
		                | ((plaintext[offset + 1] & 0xFF) << 16)
		                | ((plaintext[offset + 2] & 0xFF) << 8)
		                | (plaintext[offset + 3] & 0xFF);
		offset += 4;

		//Process received acks
		int numAcks = plaintext[offset++] & 0xFF;
		if(plaintext.length < (offset + numAcks + (numAcks > 0 ? 3 : 0))) {
			packet.error = true;
			return packet;
		}

		int prevAck = 0;
		for(int i = 0; i < numAcks; i++) {
			int ack = 0;
			if(i == 0) {
				ack = ((plaintext[offset] & 0xFF) << 24)
				                | ((plaintext[offset + 1] & 0xFF) << 16)
				                | ((plaintext[offset + 2] & 0xFF) << 8)
				                | (plaintext[offset + 3] & 0xFF);
				offset += 4;
			} else {
				ack = prevAck + (plaintext[offset++] & 0xFF);
			}
			packet.acks.add(ack);
			prevAck = ack;
		}

		//Handle received message fragments
		int prevFragmentID = -1;
		while(offset < plaintext.length) {
			boolean shortMessage = (plaintext[offset] & 0x80) != 0;
			boolean isFragmented = (plaintext[offset] & 0x40) != 0;
			boolean firstFragment = (plaintext[offset] & 0x20) != 0;

			if(!isFragmented && !firstFragment) {
				//Remainder of packet is padding
				break;
			}

			int messageID = -1;
			if((plaintext[offset] & 0x10) != 0) {
				if(plaintext.length < (offset + 4)) {
					packet.error = true;
					return packet;
				}

				messageID = ((plaintext[offset] & 0x0F) << 24)
				                | ((plaintext[offset + 1] & 0xFF) << 16)
				                | ((plaintext[offset + 2] & 0xFF) << 8)
				                | (plaintext[offset + 3] & 0xFF);
				offset += 4;
			} else {
				if(plaintext.length < (offset + 2)) {
					packet.error = true;
					return packet;
				}

				if(prevFragmentID == -1) {
					Logger.warning(NPFPacket.class, "First fragment doesn't have full message id");
					packet.error = true;
					return packet;
				}
				messageID = prevFragmentID + (((plaintext[offset] & 0x0F) << 8)
				                | (plaintext[offset + 1] & 0xFF));
				offset += 2;
			}
			prevFragmentID = messageID;

			int requiredLength = offset
			                + (shortMessage ? 1 : 2)
			                + (isFragmented ? (shortMessage ? 1 : 3) : 0);
			if(plaintext.length < requiredLength) {
				packet.error = true;
				return packet;
			}

			int fragmentLength;
			if(shortMessage) {
				fragmentLength = plaintext[offset++] & 0xFF;
			} else {
				fragmentLength = ((plaintext[offset] & 0xFF) << 8)
				                | (plaintext[offset + 1] & 0xFF);
				offset += 2;
			}

			int messageLength = -1;
			int fragmentOffset = 0;
			if(isFragmented) {
				int value;
				if(shortMessage) {
					value = plaintext[offset++] & 0xFF;
				} else {
					value = ((plaintext[offset] & 0xFF) << 8)
							| (plaintext[offset + 1] & 0xFF);
					offset += 2;
				}

				if(firstFragment) {
					messageLength = value;
					if(messageLength == fragmentLength) {
						Logger.warning(NPFPacket.class, "Received fragmented message, but fragment contains the entire message");
					}
				} else {
					fragmentOffset = value;
				}
			} else {
				messageLength = fragmentLength;
			}
			byte[] fragmentData = new byte[fragmentLength];
			if((offset + fragmentLength) > plaintext.length) {
				if(logMINOR) Logger.minor(NPFPacket.class, "Fragment doesn't fit in the received packet");
				packet.error = true;
				break;
			}
			System.arraycopy(plaintext, offset, fragmentData, 0, fragmentLength);
			offset += fragmentLength;

			packet.fragments.add(new MessageFragment(shortMessage, isFragmented, firstFragment,
			                messageID, fragmentLength, messageLength, fragmentOffset, fragmentData, null));
		}

		return packet;
	}

	public int toBytes(byte[] buf, int offset, Random paddingGen) {
		buf[offset] = (byte) (sequenceNumber >>> 24);
		buf[offset + 1] = (byte) (sequenceNumber >>> 16);
		buf[offset + 2] = (byte) (sequenceNumber >>> 8);
		buf[offset + 3] = (byte) (sequenceNumber);
		offset += 4;

		//Add acks
		buf[offset++] = (byte) (acks.size());
		int prevAck;
		Iterator<Integer> acksIterator = acks.iterator();
		if(acksIterator.hasNext()) {
			prevAck = acksIterator.next();
			buf[offset] = (byte) (prevAck >>> 24);
			buf[offset + 1] = (byte) (prevAck >>> 16);
			buf[offset + 2] = (byte) (prevAck >>> 8);
			buf[offset + 3] = (byte) (prevAck);
			offset += 4;

			while(acksIterator.hasNext()) {
				int ack = acksIterator.next();
				buf[offset++] = (byte) (ack - prevAck);
				prevAck = ack;
			}
		}

		//Add fragments
		int prevFragmentID = -1;
		for(MessageFragment fragment : fragments) {
			if(fragment.shortMessage) buf[offset] = (byte) ((buf[offset] & 0xFF) | 0x80);
			if(fragment.isFragmented) buf[offset] = (byte) ((buf[offset] & 0xFF) | 0x40);
			if(fragment.firstFragment) buf[offset] = (byte) ((buf[offset] & 0xFF) | 0x20);

			if(prevFragmentID == -1 || (fragment.messageID - prevFragmentID >= 4096)) {
				buf[offset] = (byte) ((buf[offset] & 0xFF) | 0x10);
				buf[offset] = (byte) ((buf[offset] & 0xFF) | ((fragment.messageID >>> 24) & 0x0F));
				buf[offset + 1] = (byte) (fragment.messageID >>> 16);
				buf[offset + 2] = (byte) (fragment.messageID >>> 8);
				buf[offset + 3] = (byte) (fragment.messageID);
				offset += 4;
			} else {
				int compressedMsgID = fragment.messageID - prevFragmentID;
				buf[offset] = (byte) ((buf[offset] & 0xFF) | ((compressedMsgID >>> 8) & 0x0F));
				buf[offset + 1] = (byte) (compressedMsgID);
				offset += 2;
			}
			prevFragmentID = fragment.messageID;

			if(fragment.shortMessage) {
				buf[offset++] = (byte) (fragment.fragmentLength);
			} else {
				buf[offset] = (byte) (fragment.fragmentLength >>> 8);
				buf[offset + 1] = (byte) (fragment.fragmentLength);
				offset += 2;
			}

			if(fragment.isFragmented) {
				// If firstFragment is true, add total message length. Else, add fragment offset
				int value = fragment.firstFragment ? fragment.messageLength : fragment.fragmentOffset;

				if(fragment.shortMessage) {
					buf[offset++] = (byte) (value);
				} else {
					buf[offset] = (byte) (value >>> 8);
					buf[offset + 1] = (byte) (value);
					offset += 2;
				}
			}

			System.arraycopy(fragment.fragmentData, 0, buf, offset, fragment.fragmentLength);
			offset += fragment.fragmentLength;
		}

		if(offset < buf.length) {
			//More room, so add padding
			byte[] padding = new byte[buf.length - offset];
			paddingGen.nextBytes(padding);
			System.arraycopy(padding, 0, buf, offset, padding.length);

			buf[offset] = (byte) (buf[offset] & 0x9F); //Make sure firstFragment and isFragmented isn't set
		}

		return offset;
	}

	public boolean addAck(int ack) {
		if(ack < 0) throw new IllegalArgumentException("Got negative ack: " + ack);
		if(acks.contains(ack)) return true;
		if(acks.size() >= 255) return false;

		if(acks.size() == 0) {
			length += 3;
		} else if(ack < acks.first()) {
			if((acks.first() - ack) > 255) return false;
		} else if(ack > acks.last()) {
			if((ack - acks.last()) > 255) return false;
		}
		acks.add(ack);
		length++;

		return true;
	}

	private int oldMsgIDLength;
	public int addMessageFragment(MessageFragment frag) {
		length += frag.length();
		fragments.add(frag);
		Collections.sort(fragments, new MessageFragmentComparator());

		int msgIDLength = 0;
		int prevMessageID = -1;
		for(MessageFragment fragment : fragments) {
			if((prevMessageID == -1) || (fragment.messageID - prevMessageID >= 4096)) {
				msgIDLength += 2;
			}
			prevMessageID = fragment.messageID;
		}

		length += (msgIDLength - oldMsgIDLength);
		oldMsgIDLength = msgIDLength;

		return length;
	}

	public boolean getError() {
		return error;
        }

	public LinkedList<MessageFragment> getFragments() {
		return fragments;
        }

	public int getSequenceNumber() {
		return sequenceNumber;
        }

	public void setSequenceNumber(int sequenceNumber) {
		this.sequenceNumber = sequenceNumber;
	}

	public SortedSet<Integer> getAcks() {
		return acks;
        }

	public int getLength() {
		return length;
	}

	public String toString() {
		return "Packet " + sequenceNumber + ": " + length + " bytes, " + acks.size() + " acks, " + fragments.size() + " fragments";
	}

	private static class MessageFragmentComparator implements Comparator<MessageFragment> {
		public int compare(MessageFragment frag1, MessageFragment frag2) {
			if(frag1.messageID < frag2.messageID) return -1;
			if(frag1.messageID == frag2.messageID) return 0;
			return 1;
		}
	}
}