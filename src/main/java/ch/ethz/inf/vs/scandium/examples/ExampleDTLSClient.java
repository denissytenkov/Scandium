/*******************************************************************************
 * Copyright (c) 2013, Institute for Pervasive Computing, ETH Zurich.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * 
 * This file is part of Scandium (Sc) Security for Californium.
 ******************************************************************************/

package ch.ethz.inf.vs.scandium.examples;

import java.io.IOException;
import java.net.InetAddress;

import ch.ethz.inf.vs.scandium.EndpointAddress;
import ch.ethz.inf.vs.scandium.RawData;
import ch.ethz.inf.vs.scandium.RawDataChannel;
import ch.ethz.inf.vs.scandium.connector.Connector;
import ch.ethz.inf.vs.scandium.connector.DTLSConnector;
import ch.ethz.inf.vs.scandium.util.ScProperties;

public class ExampleDTLSClient {

	public static final int DEFAULT_PORT = ScProperties.std.getInt("DEFAULT_PORT");
	
	private Connector dtlsConnector;
	
	public ExampleDTLSClient() {
		dtlsConnector = new DTLSConnector(new EndpointAddress(0));
		dtlsConnector.setRawDataReceiver(new RawDataChannelImpl(dtlsConnector));
	}
	
	public void send() {
		try {
			dtlsConnector.start();
			dtlsConnector.send(new RawData("HELLO WORLD".getBytes(), InetAddress.getByName("localhost") , DEFAULT_PORT));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private class RawDataChannelImpl implements RawDataChannel {
		
		private Connector connector;
		
		public RawDataChannelImpl(Connector con) {
			this.connector = con;
		}

		@Override
		public void sendData(RawData raw) {
			connector.send(raw);
		}

		@Override
		public void receiveData(final RawData raw) {
			if (raw.getAddress() == null)
				throw new NullPointerException();
			if (raw.getPort() == 0)
				throw new NullPointerException();
			
			System.out.println(new String(raw.getBytes()));
			System.exit(0);
		}

	}
	
	public static void main(String[] args) {

		ExampleDTLSClient client = new ExampleDTLSClient();
		client.send();
	}

}