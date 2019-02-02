import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

abstract class pRes {
    static ServerSocket serverSocket;
    static List<Room> roomList;
}

abstract class Cmd {
    static final int KEY = 102992;
    static final int READY = 4734328;
    static final int START = 2847123;
    static final int CHAT = 7736283;
    static final int CLICK_UNIT = 873924;
    static final int GAME_END = 19823;
}

// 한방 인원이 2명이 최대인 룸 클래스
class Room {
    private List<Socket> socketList; // 클라이언트의 소켓이 순차 저장되는 리스트

    Room() {
        socketList = new ArrayList<>();
    }

    // 방에 새로운 클라이언트 입장
    void addClient(Socket clientSocket) {
        socketList.add(clientSocket);
    }

    // 이 방에 몇명이 있는지 알려주는 함수
    int getClientCount() {
        return socketList.size();
    }

    // 방의 모든 클라이언트에게 메시지 전송
    void broadcast(JSONObject json) {
        for (Socket curSocket : socketList) {
            try {
                new DataOutputStream(curSocket.getOutputStream()).writeUTF(json.toString());
            } catch (IOException ignored) {
            }
        }
    }

    // 오직 방장에게만 메시지 전송
    void sendToMaster(JSONObject json) {
        try {
            new DataOutputStream(socketList.get(0).getOutputStream()).writeUTF(json.toString());
        } catch (Exception ignored) {
        }
    }

    // 오직 유저에게만 메시지 전송
    void sendToClient(JSONObject json) {
        try {
            new DataOutputStream(socketList.get(1).getOutputStream()).writeUTF(json.toString());
        } catch (Exception ignored) {
        }
    }

    // 서버와 유저들의 연결 종료
    void disconnect() {
        for (Socket curSocket : socketList) {
            try {
                curSocket.getOutputStream().close();
            } catch (Exception ignored) {
            }
        }
    }
}

// 클라이언트와 직접적인 소통이 일어나는 스레드
class StartGame extends Thread {
    private Socket clientSocket; // 접속한 클라이언트의 소켓
    private Room myRoom; // 자기가 어떤 방에 속해있는지 알기 위한 레퍼런스
    private boolean isMaster; // 자신이 방장인지 아닌지를 알려주는 변수

    StartGame(Socket clientSocket, Room myRoom, boolean isMaster) {
        this.clientSocket = clientSocket;
        this.myRoom = myRoom;
        this.isMaster = isMaster;
    }

    @Override
    public void run() {
        try {
            // 초기 서버 접속 시 방장 여부 전송
            JSONObject json = new JSONObject();
            if (isMaster) {
                json.put("isMaster", true);
                myRoom.sendToMaster(json);
            } else {
                json.put("isMaster", false);
                myRoom.sendToClient(json);
            }

            // 초기 입장 메시지 전송
            json = new JSONObject();
            if(isMaster){
                json.put("cmd", Cmd.CHAT);
                json.put("msg", "[상대방의 입장을 대기중입니다]");
                myRoom.sendToMaster(json);
            } else {
                json.put("cmd", Cmd.CHAT);
                json.put("msg", "[상대방과 연결되었습니다]");
                myRoom.broadcast(json);
            }

            DataInputStream dis = new DataInputStream(clientSocket.getInputStream());

            // 클라이언트에게 커맨드를 입력받아 브로드캐스팅 해주는 루프
            while (true) {
                try {
                    json = new JSONObject(dis.readUTF());
                } catch (Exception disconnectException) {
                    // IOException : 클라이언트의 연결이 종료됨
                    // 루프 탈출
                    break;
                }

                switch ((int) json.get("cmd")) {
                    // 게임 준비
                    case Cmd.READY:
                        myRoom.sendToMaster(json);
                        break;

                        // 게임 시자
                    case Cmd.START:
                        myRoom.broadcast(json);
                        break;

                        // 채팅
                    case Cmd.CHAT:
                        myRoom.broadcast(json);
                        break;

                        // 빙고판 숫자 클릭
                    case Cmd.CLICK_UNIT:
                        myRoom.broadcast(json);
                        break;

                        // 게임 종료
                    case Cmd.GAME_END:
                        myRoom.broadcast(json);
                        break;
                }
            }

            // 방에 접속한 클라이언트와 연결 종료 후 방 리스트에서 해당 방 삭제
            myRoom.disconnect();
            pRes.roomList.remove(myRoom);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

// 클라이언트의 서버 접속 이전에 키값을 검사하고 어떤 방으로 안내할지 알려주는 게이트 스레드
class Gate extends Thread {
    private Socket clientSocket;

    Gate(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try {
            DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
            JSONObject json = new JSONObject(dis.readUTF());

            // 키값 일치하지 않을 경우 비정상 접속으로 간주해 연결 차단
            if ((int) json.get("key") != Cmd.KEY) {
                System.out.println("[키 일치 실패 : 서버 입장 불가]");
                disconnect(clientSocket);
                return;
            }

            // 클라이언트가 어떤 방으로 갈지 안내해주는 부분
            // 대기중인 방이 있다면 해당 방으로 이동 후 유저로 지정
            // 그렇지 않다면 새로운 방을 만들어 방장으로 지정
            Room myRoom = null;
            boolean isMaster = false;
            if (pRes.roomList.size() == 0) {
                myRoom = new Room();
                isMaster = true;
            } else if (pRes.roomList.get(pRes.roomList.size() - 1).getClientCount() == 1) {
                myRoom = pRes.roomList.get(pRes.roomList.size() - 1);
                isMaster = false;
            } else if (pRes.roomList.get(pRes.roomList.size() - 1).getClientCount() == 2) {
                myRoom = new Room();
                isMaster = true;
            }

            pRes.roomList.add(myRoom);
            assert myRoom != null;
            myRoom.addClient(clientSocket);
            new StartGame(clientSocket, myRoom, isMaster).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 클라이언트의 소켓과 연결을 종료
    private void disconnect(Socket clientSocket) {
        try {
            clientSocket.getOutputStream().close();
        } catch (Exception ignored) {
        }
    }
}

// 서버를 열기 위한 소켓을 초기화하고 유저의 접속을 받아주는 서버 클래스
class Server {
    void start() {
        try {
            pRes.serverSocket = new ServerSocket(8080);
            pRes.roomList = new ArrayList<>();
            while (true) {
                Socket clientSocket = pRes.serverSocket.accept();
                new Gate(clientSocket).start();
            }
        } catch (Exception e) {
            System.out.println("서버 초기화에 실패했습니다. 포트가 이미 사용중입니다.");
        }
    }
}

// main 클래스
public class BingoServer {
    public static void main(String[] args) {
        new Server().start();
    }
}
