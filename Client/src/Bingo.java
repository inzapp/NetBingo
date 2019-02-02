import javafx.application.Application;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Random;
import java.util.ResourceBundle;

abstract class pRes {
    static Socket socket; // 서버 통신에 필요한 소켓
    static boolean isMaster; // 접속한 클라이언트가 방장인지 유저인지를 판단하는 변수
    static int bingoCount = 0;
    static PanElement[][] panElementArr; // 빙고판 값을 가지는 2차원 배열

    // JSON 객체를 서버로 전송하는 함수
    static void send(JSONObject json) {
        new Thread(() -> {
            try {
                new DataOutputStream(socket.getOutputStream()).writeUTF(json.toString());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}

// FX 뷰 레퍼런스들을 공유자원으로서 사용하기 위한 추상클래스
abstract class View {
    static ImageView[][] pan;
    static TextArea chatLog;
    static TextField inputChat;
    static Button sendBt, readyStartBt, exitBt;
}

// JSON 통신간 명령 규약
abstract class Cmd {
    static final int KEY = 102992;
    static final int READY = 4734328;
    static final int START = 2847123;
    static final int CHAT = 7736283;
    static final int CLICK_UNIT = 873924;
    static final int GAME_END = 19823;
}

// 빙고판의 값과 클릭이 되었는지 여부를 알 수 있는 클래스
class PanElement {
    private int val;
    private boolean isClicked;

    PanElement(int val) {
        this.val = val;
        this.isClicked = false;
    }

    int getVal() {
        return this.val;
    }

    boolean isClicked() {
        return this.isClicked;
    }

    void setClick() {
        this.isClicked = true;
    }
}

// 빙고게임 시작이 됐을때 빙고판 이미지와 빙고판 값을 세팅해주는 클래스
class BingoGame extends Thread {
    BingoGame() {
        pRes.panElementArr = new PanElement[5][5];
        pRes.bingoCount = 0;

        // 첫 턴이 방장에게 있음을 알림
        if(pRes.isMaster){
            for(int i=0; i<5; i++){
                for(int j=0; j<5; j++){
                    View.pan[i][j].setDisable(false);
                }
            }
        } else{
            for(int i=0; i<5; i++){
                for(int j=0; j<5; j++){
                    View.pan[i][j].setDisable(true);
                }
            }
        }
    }

    @Override
    public void run() {
        // 랜덤 빙고판을 생성하기 위한 과정

        // 1. 25개의 일차원 배열을 선언 후 1 ~ 25 로 초기화
        int tempArr[] = new int[25];
        for (int i = 0; i < 25; i++) {
            tempArr[i] = i + 1;
        }

        // 2. Random 클래스를 이용해 이 배열을 100회 무작위로 섞음
        Random random = new Random();
        int randIndex, temp;
        for (int i = 0; i < 100; i++) {
            randIndex = random.nextInt(24);
            temp = tempArr[0];
            tempArr[0] = tempArr[randIndex];
            tempArr[randIndex] = temp;
        }

        // 3. 빙고판 형식에 맞게 2차원 배열을 생성하고 섞인 1차원 배열의 값을 순차 대입
        int incVal = 0;
        int[][] panVal = new int[5][5];
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                panVal[i][j] = tempArr[incVal++];
            }
        }

        // 4. 빙고판의 실제 값을 세팅해주고 그 값에 맞는 이미지를 출력
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                pRes.panElementArr[i][j] = new PanElement(panVal[i][j]);
                View.pan[i][j].setImage(getNotClickedImage(panVal[i][j]));
            }
        }
    }

    // 클릭이 안된 숫자 이미지 ( "n1.png" ) 를 가져오는 함수
    private Image getNotClickedImage(int val) {
        return new Image(getClass().getResource("n" + val + ".png").toString());
    }
}

// 서버와 통신하기 위한 리시브 스레드 클래스
class Receive extends Thread {
    @Override
    public void run() {
        try {
            DataInputStream dis = new DataInputStream(pRes.socket.getInputStream());
            JSONObject json;

            while (true) {
                try {
                    json = new JSONObject(dis.readUTF());
                } catch (Exception e) {
                    // 통신 중단 시 메시지 출력 후 스레드 종료
                    View.chatLog.appendText("[상대방이 나갔습니다]" + "\r\n");
                    return;
                }

                // 중단이 되지 않았다면 Cmd 클래스의 규약에 맞게 해당 작업 실행
                switch ((int) json.get("cmd")) {

                    // 채팅 커맨드
                    case Cmd.CHAT:
                        String msg = (String) json.get("msg");
                        View.chatLog.appendText(msg + "\r\n");
                        View.chatLog.setScrollTop(Double.MAX_VALUE);
                        break;

                        // 준비완료 커맨드
                    case Cmd.READY:

                        // 준비 완료는 방장만 알 필요가 있으므로 방장의 클라이언트에게 시작버튼 활성화 / 비활성화
                        if (pRes.isMaster) {
                            if (View.readyStartBt.isDisable()) View.readyStartBt.setDisable(false);
                            else View.readyStartBt.setDisable(true);
                        }
                        break;

                        // 게임 시작 커맨드
                    case Cmd.START:
                        // 빙고판 생성 스레드 실행
                        new BingoGame().start();
                        break;

                        // 빙고 판의 숫자 버튼을 눌렀을때 해당 숫자가 눌렸다는걸 세팅해주고 이미지 수정
                    case Cmd.CLICK_UNIT:

                        // 한번 숫자를 클릭할때마다 숫자를 못누르게 / 누르게 변경하여 턴을 맞춰줌
                        boolean isDisable = View.pan[0][0].isDisable();
                        if(isDisable){
                            for(int i=0; i<5; i++){
                                for(int j=0; j<5; j++){
                                    View.pan[i][j].setDisable(false);
                                }
                            }
                        } else{
                            for(int i=0; i<5; i++){
                                for(int j=0; j<5; j++){
                                    View.pan[i][j].setDisable(true);
                                }
                            }
                        }

                        // 몇번 숫자를 클릭했는지 가져옴
                        int clickVal = (int) json.get("clickVal");

                        // 빙고판에서 해당 숫자의 클릭값을 참으로 설정하고 클릭이 된 이미지로 변경해줌
                        for (int i = 0; i < 5; i++) {
                            for (int j = 0; j < 5; j++) {
                                if (clickVal == pRes.panElementArr[i][j].getVal()) {
                                    pRes.panElementArr[i][j].setClick();
                                    View.pan[i][j].setImage(getClickedImage(clickVal));
                                    break;
                                }
                            }
                        }

                        // 여기서부턴 사용자가 몇 빙고를 했는지 세는 부분
                        // 한줄 한줄 검사하며 빙고 카운트를 누적해 나감
                        int curBingoCount = 0;
                        int count = 0;
                        for (int j = 0; j < 5; j++) {
                            if (pRes.panElementArr[0][j].isClicked()) count++;
                        }
                        if (count == 5) curBingoCount++;

                        count = 0;
                        for (int j = 0; j < 5; j++) {
                            if (pRes.panElementArr[1][j].isClicked()) count++;
                        }
                        if (count == 5) curBingoCount++;

                        count = 0;
                        for (int j = 0; j < 5; j++) {
                            if (pRes.panElementArr[2][j].isClicked()) count++;
                        }
                        if (count == 5) curBingoCount++;

                        count = 0;
                        for (int j = 0; j < 5; j++) {
                            if (pRes.panElementArr[3][j].isClicked()) count++;
                        }
                        if (count == 5) curBingoCount++;

                        count = 0;
                        for (int j = 0; j < 5; j++) {
                            if (pRes.panElementArr[4][j].isClicked()) count++;
                        }
                        if (count == 5) curBingoCount++;

                        count = 0;
                        for (int i = 0; i < 5; i++) {
                            if (pRes.panElementArr[i][0].isClicked()) count++;
                        }
                        if (count == 5) curBingoCount++;

                        count = 0;
                        for (int i = 0; i < 5; i++) {
                            if (pRes.panElementArr[i][1].isClicked()) count++;
                        }
                        if (count == 5) curBingoCount++;

                        count = 0;
                        for (int i = 0; i < 5; i++) {
                            if (pRes.panElementArr[i][2].isClicked()) count++;
                        }
                        if (count == 5) curBingoCount++;

                        count = 0;
                        for (int i = 0; i < 5; i++) {
                            if (pRes.panElementArr[i][3].isClicked()) count++;
                        }
                        if (count == 5) curBingoCount++;

                        count = 0;
                        for (int i = 0; i < 5; i++) {
                            if (pRes.panElementArr[i][4].isClicked()) count++;
                        }
                        if (count == 5) curBingoCount++;

                        count = 0;
                        for (int x = 0; x < 5; x++) {
                            if (pRes.panElementArr[x][x].isClicked()) count++;
                        }
                        if (count == 5) curBingoCount++;

                        // 중복된 빙고카운트르 알리지 않기 위한 비교 후 알림
                        if (pRes.bingoCount < curBingoCount) {
                            pRes.bingoCount = curBingoCount;

                            // 서버접속 초기에 받았던 방장인지 여부 를 알리는 isMaster 변수의 값에 따라 아이디 지정
                            String myId = pRes.isMaster ? "방장 " : "유저 ";

                            // 빙고의 결과를 채팅으로 알림
                            json = new JSONObject();
                            json.put("cmd", Cmd.CHAT);
                            json.put("msg", "[" + myId + "님 " + pRes.bingoCount + " 빙고 !!!]" + "\r\n");
                            pRes.send(json);

                            // 만약 빙고가 5 이상이라면 게임을 끝냄
                            if (5 <= pRes.bingoCount) {
                                json = new JSONObject();
                                json.put("cmd", Cmd.GAME_END);
                                json.put("winner", myId);
                                pRes.send(json);
                            }
                        }
                        break;

                        // 게임이 끝난 커맨드
                    case Cmd.GAME_END:
                        // 방장과 유저 중 어떤 사용자가 게임에서 이겼는지를 받아옴
                        String winnerId = (String) json.get("winner");

                        // 누가 이겼는지 채팅창에 출력
                        View.chatLog.appendText("[" + winnerId + "님이 이겼습니다]" + "\r\n");

                        // 이후 빙고판을 비활성화 함으로서 게임 종료
                        for (int i = 0; i < 5; i++) {
                            for (int j = 0; j < 5; j++) {
                                View.pan[i][j].setDisable(true);
                            }
                        }

                        // 방장에게는 시작 버튼 비활성화 : 유저의 준비를 기다리게 됨
                        if (pRes.isMaster) {
                            View.readyStartBt.setDisable(true);
                        }
                        break;

                    default:
                        break;

                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 클릭이 된 나무모양의 숫자 이미지를 가져오는 함수
    private Image getClickedImage(int val) {
        return new Image(getClass().getResource("p" + val + ".png").toString());
    }
}

// 서버에 연결하는 스레드
class ServerConnect extends Thread {
    @Override
    public void run() {
        try {
            disconnect(pRes.socket);
            pRes.socket = new Socket();
            pRes.socket.connect(new InetSocketAddress("127.0.0.1", 8080), 5000);

            // 초기 서버 접속을 위한 키값 전송
            JSONObject json = new JSONObject();
            json.put("key", Cmd.KEY);
            pRes.send(json);

            DataInputStream dis = new DataInputStream(pRes.socket.getInputStream());
            json = new JSONObject(dis.readUTF());


            pRes.isMaster = (boolean) json.get("isMaster");

            // 방장에게는 시작 버튼을 유저에게는 준비 버튼을 띄워 줌
            if (pRes.isMaster) {
                View.readyStartBt.setText("시작");
                View.readyStartBt.setDisable(true);

                // 초기 빙고판 비활성화
                for(int i=0; i<5; i++){
                    for(int j=0; j<5; j++){
                        View.pan[i][j].setDisable(false);
                    }
                }
            } else {
                View.readyStartBt.setText("준비");

                // 초기 빙고판 비활성화
                for(int i=0; i<5; i++){
                    for(int j=0; j<5; j++){
                        View.pan[i][j].setDisable(true);
                    }
                }
            }
        } catch (Exception e) {
            View.chatLog.appendText("[서버 초기화 실패]");
            e.printStackTrace();
        }

        // 리시브 스레드를 실행하여 서버로부터 결과를 전송받음
        new Receive().start();
    }

    // 클라리언트의 접속을 종료
    private void disconnect(Socket socket) {
        try {
            socket.getOutputStream().close();
        } catch (Exception ignored) {
        }
    }
}

// FXML 의 UI 설정과 공유자원으로 쓰기 위한 설정이 포함된 초기화 클래스
public class Bingo extends Application implements Initializable {
    // 빙고판 숫자 버튼 뷰들
    @FXML
    private ImageView b00, b01, b02, b03, b04;
    @FXML
    private ImageView b10, b11, b12, b13, b14;
    @FXML
    private ImageView b20, b21, b22, b23, b24;
    @FXML
    private ImageView b30, b31, b32, b33, b34;
    @FXML
    private ImageView b40, b41, b42, b43, b44;

    @FXML
    private TextArea chatLog; // 채팅의 로그가 저장될 텍스트 에어리어
    @FXML
    private TextField inputChat; // 채팅을 보내기 위한 텍스트 필드
    @FXML
    private Button sendBt, readyStartBt, exitBt; // 전송, 준비/시작, 나가기 버튼

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        View.pan = new ImageView[5][5];
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                View.pan[i][j] = new ImageView();
            }
        }

        View.pan[0][0] = this.b00;
        View.pan[0][1] = this.b01;
        View.pan[0][2] = this.b02;
        View.pan[0][3] = this.b03;
        View.pan[0][4] = this.b04;

        View.pan[1][0] = this.b10;
        View.pan[1][1] = this.b11;
        View.pan[1][2] = this.b12;
        View.pan[1][3] = this.b13;
        View.pan[1][4] = this.b14;

        View.pan[2][0] = this.b20;
        View.pan[2][1] = this.b21;
        View.pan[2][2] = this.b22;
        View.pan[2][3] = this.b23;
        View.pan[2][4] = this.b24;

        View.pan[3][0] = this.b30;
        View.pan[3][1] = this.b31;
        View.pan[3][2] = this.b32;
        View.pan[3][3] = this.b33;
        View.pan[3][4] = this.b34;

        View.pan[4][0] = this.b40;
        View.pan[4][1] = this.b41;
        View.pan[4][2] = this.b42;
        View.pan[4][3] = this.b43;
        View.pan[4][4] = this.b44;

        View.chatLog = this.chatLog;
        View.inputChat = this.inputChat;

        View.sendBt = this.sendBt;
        View.readyStartBt = this.readyStartBt;
        View.exitBt = this.exitBt;

        // 채팅로그 편집 불가 설정
        View.chatLog.setEditable(false);
        View.chatLog.setWrapText(true);

        // 채팅창에서 엔터키 눌렀을 때 전송 되게끔 설정
        View.inputChat.setOnKeyPressed(event -> {
            if (event.getCode().equals(KeyCode.ENTER)) {
                try {
                    JSONObject json = new JSONObject();
                    json.put("cmd", Cmd.CHAT);

                    String myId = pRes.isMaster ? "방장 : " : "유저 : ";
                    json.put("msg", myId + View.inputChat.getText());
                    pRes.send(json);
                    View.inputChat.setText("");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        // 시작/준비 버튼 클릭 시 이벤트
        View.readyStartBt.setOnMouseClicked(event -> {
            JSONObject json = new JSONObject();
            if (pRes.isMaster) {
                try {
                    json.put("cmd", Cmd.START);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                try {
                    json.put("cmd", Cmd.READY);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            pRes.send(json);
        });

        // 전송 버튼 클릭 시 이벤트
        View.sendBt.setOnMouseClicked(event -> {
            try {
                JSONObject json = new JSONObject();
                json.put("cmd", Cmd.CHAT);

                String myId = pRes.isMaster ? "방장 : " : "유저 : ";
                json.put("msg", myId + View.inputChat.getText());
                pRes.send(json);
                View.inputChat.setText("");
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // 나가기 버튼을 눌렀을때 프로그램 종료
        View.exitBt.setOnMouseClicked(event -> System.exit(0));

        // 빙고 이미지 버튼 클릭 시 이벤트 설정
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                int finalI = i;
                int finalJ = j;
                View.pan[i][j].setOnMouseClicked(event -> {
                    try {
                        int clickVal = pRes.panElementArr[finalI][finalJ].getVal();

                        JSONObject json = new JSONObject();
                        json.put("cmd", Cmd.CLICK_UNIT);
                        json.put("clickVal", clickVal);

                        pRes.send(json);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        }

        // 서버 접속요청 후 통신 시작
        new ServerConnect().start();
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        // FXML 파일을 로드해 윈도우 창을 띄워 줌
        Parent fxml = FXMLLoader.load(getClass().getResource("bingo.fxml"));
        primaryStage.setScene(new Scene(fxml));
        primaryStage.setTitle("Bingo");
        primaryStage.setResizable(false);
        primaryStage.setOnCloseRequest(event -> System.exit(0));
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
