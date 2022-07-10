package com.smtix.serialportsimplepainter;

import jssc.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.util.regex.PatternSyntaxException;

class CorruptedDataException extends Exception {
    @Override
    public String toString() {
        return "Reading corrupted data!";
    }
}

class GraphicPencilSTM32 extends JPanel {

    private Color c;
    private int x, y, xi, yi, zi, sizeX, sizeY; //x и у - абсолютные координаты точки; xi, yi и zi - показания акселерометра; sizeX, sizeY - размер окна
    private SerialPort serialPort = null;
    private String data; //Считываемая строка
    private boolean dataError = false, isXZero = false, isYZero = false; //dataError - флаг ошибки чтения строки; isXZero, isYZero - проверка на нулевые координаты

    GraphicPencilSTM32(int sizeX, int sizeY) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        x = sizeX / 2; //По умолчанию - точка находится в центре экрана
        y = sizeY / 2;
        c = Color.getHSBColor((float)Math.random() * 255, (float)Math.random() * 255, (float)Math.random() * 255);
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponents(g);

        g.setColor(c);

        //По флагу определяем - если точка (x, y) находятся на нулевой границе экрана, то не отрисовываем дополнительные точки
        xi = (isXZero) ? 0 : xi;
        yi = (isYZero) ? 0 : yi;

        //Если точка (x, y) находятся на нулевой границе экрана, то ставим специальный флаг
        isXZero = (x == 0 || x == sizeX);
        isYZero = (y == 0 || y == sizeY);

        //Помимо основной точки, рисуется n дополнительных точек (по максимальному расстоянию между двумя точками), чтобы убрать пробелы между двумя точками
        int max = Math.max(Math.abs(xi), Math.abs(yi));
        g.fillOval(x, y, 10, 10);
        for (int i = 1; i <= max; i++) {
            g.fillOval(x + i * xi / max, y + i * yi / max, 10, 10);
        }
    }

    private void findAndSetPort(SerialPortEventListener event) {
        try {
            //Находим список COM-портов
            String[] list = SerialPortList.getPortNames();

            //Из списка находим нужный порт, подключаем устройство
            for (String port : list) {
                serialPort = new SerialPort(port);
                serialPort.openPort();
                serialPort.setParams(115200, 8, 1, 0, true, true);
                serialPort.setEventsMask(SerialPort.MASK_RXCHAR + SerialPort.MASK_CTS + SerialPort.MASK_DSR);
                if (serialPort.readBytes() != null) { //Если нашли нужный - закрываем цикл
                    System.out.println(port); //Выводим используемый порт
                    break;
                }
                serialPort.closePort();  //Иначе закрываем порт
            }

            //Начинаем считывать данные через eventListener
            serialPort.addEventListener(event);

        } catch (SerialPortException exc) {
            System.out.println(exc.toString());
        }
    }

    void draw() {
        //Создаем рабочее окно
        JFrame frame = new JFrame();
        frame.setVisible(true);
        frame.setPreferredSize(new Dimension(sizeX, sizeY));
        frame.setResizable(false);
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(this);

        try {
            //Считываем данные через eventListener, передаем лямбда-выражение в addEventListener
            SerialPortEventListener eventListener = (event) -> {
                if (event.isRXCHAR()) {
                    try {
                        data = serialPort.readString(28); //Читаем строку

                        //Задержка в 0.01 секунду
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException exc) {
                            System.out.println(exc.toString());
                        }

                        //Проверяем строку на соответствие с шаблоном (пример шаблона: "X:00142 Y:-000243 Z:000041")
                        if (data.matches("X:-?\\d{5,6}\\sY:-?\\d{5,6}\\s*Z:-?\\d{5,6}\\s*")) {
                            //Разбиваем строку на подстроки c x, y, z
                            String[] cords = data.split("\\s");

                            //Получаем показания акселерометра из строки. Здесь оси X и Y изменены местами, а полученные числа делится на 10 (убирается разряд единиц)
                            xi = Integer.parseInt(cords[1].substring(2, cords[1].length() - 1));
                            yi = Integer.parseInt(cords[0].substring(2, cords[0].length() - 1));
                            zi = Integer.parseInt(cords[2].substring(2, cords[2].length() - 1));

                            //Смещаем точку (x, y) по показаниям
                            x -= xi;
                            x = Math.max(0, x); //Устанавливаем границу экрана, чтобы координаты точки не уходили ниже нуля или больше размера экрана
                            x = Math.min(sizeX, x);
                            y -= yi;
                            y = Math.max(0, y);
                            y = Math.min(sizeY, y);

                            //Для координаты z - меняем цвет
                            if (Math.abs(zi) > 50) {
                                c = Color.getHSBColor((float)Math.random() * 255, (float)Math.random() * 255, (float)Math.random() * 255);
                            }

                            //System.out.println("Координаты точки: x = " + x + " y = " + y);
                            repaint(); //Рисуем (метод paintComponent())
                            dataError = false;
                        } else { //В случае неправильно прочитанной строки пытаемся восстановить её, прочитав n байт до символа '\n'
                            int index = data.indexOf("\n");
                            if (index >= 0) {
                                data = serialPort.readString(data.indexOf("\n") + 1);
                            }
                            dataError = true; //Флаг служит в качестве счетчика ошибочных строк. Если они повторяются множество раз - цикл необходимо прекратить
                        }
                    } catch (SerialPortException | ArrayIndexOutOfBoundsException | PatternSyntaxException exc) {
                        System.out.println(exc.toString());
                    }
                }
            };

            //Связываемся с портом, открываем его и передаем фрейм и лямбда-выражение
            findAndSetPort(eventListener);

            //Проверяем, удалось ли реализовать соединение (если объект равен null - генерируем исключение)
            if (serialPort == null) {
                throw new NullPointerException();
            }

            //Цикл проверки - доступно ли соединение и правильно ли считываются строки
            int count = 0; //Счетчик ошибок
            while((serialPort.getLinesStatus())[0] != 0) { //Цикл работает, пока устройство не отключено от питания

                if (dataError) { //Если данные считываются неправильно
                    count++; //То добавляем +1 к счетчику ошибок
                    if (count > 500) { //В случае 500 ошибок, идущих подряд - выбрасывается исключение
                        throw new CorruptedDataException();
                    }
                } else {
                    count = 0;
                }

                //Задержка в 0.01 секунду
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exc) {
                    System.out.println(exc.toString());
                }
            }

        } catch (SerialPortException | NullPointerException | CorruptedDataException exc) {
            System.out.println(exc.toString());
        } finally { //Закрываем порт и фрейм
            if (serialPort != null) {
                try {
                    serialPort.closePort();
                }
                catch (SerialPortException exc) {
                    System.out.println(exc.toString());
                }
            }
            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
        }
    }
}

public class Main {
    public static void main(String[] args) {

        int width, height;

        if (args.length >= 2) { //При передачи параметров можем установить размер окна
            width = Integer.parseInt(args[0]);
            height = Integer.parseInt(args[1]);
        } else { //Или размер равен размеру монитора
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            width = (int) (screenSize.getWidth());
            height = (int) (screenSize.getHeight());
        }

        GraphicPencilSTM32 gp = new GraphicPencilSTM32(width, height);
        gp.draw();
    }
}
