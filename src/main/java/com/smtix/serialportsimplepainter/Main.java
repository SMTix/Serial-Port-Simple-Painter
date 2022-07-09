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
    private int x, y, xi, yi, sizeX, sizeY, zi;
    private SerialPort serialPort = null;
    private String data;
    private boolean dataError = false, isXZero = false, isYZero = false;

    GraphicPencilSTM32(int sizeX, int sizeY) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        x = sizeX / 2;
        y = sizeY / 2;
        c = Color.getHSBColor((float)Math.random() * 255, (float)Math.random() * 255, (float)Math.random() * 255);
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponents(g);

        g.setColor(c);

        //Если x, y находятся на границах, то мы не отрисовываем дополнительные точки
        xi = (isXZero) ? 0 : xi;
        yi = (isYZero) ? 0 : yi;

        //Если x, y упали на границу экрана, то ставим специальный флаг (подсчет идет только во второй раз)
        isXZero = (x == 0 || x == sizeX);
        isYZero = (y == 0 || y == sizeY);

        //Рисуется n точек (по максимальному расстоянию между двумя точками), чтобы убрать пробелы между двумя точками
        int max = Math.max(Math.abs(xi), Math.abs(yi));
        g.fillOval(x, y, 10, 10);
        for (int i = 1; i <= max; i++) {
            g.fillOval(x + i * xi / max, y + i * yi / max, 10, 10);
        }
    }

    private void findAndSetPort(SerialPortEventListener event) {
        try {
            String[] list = SerialPortList.getPortNames(); //Находим порты

            //Подключаем устройство по порту
            for (String port : list) {
                System.out.println(port);
                serialPort = new SerialPort(port);
                serialPort.openPort();
                serialPort.setParams(115200, 8, 1, 0, true, true);
                serialPort.setEventsMask(SerialPort.MASK_RXCHAR + SerialPort.MASK_CTS + SerialPort.MASK_DSR);
                if (serialPort.readBytes() != null) { //Если нашли нужный - закрываем цикл
                    break;
                }
                serialPort.closePort();  //Иначе закрываем порт
            }

            //Считываем данные через eventListener
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
                        //System.out.print("Строка: " + data);

                        //Задержка в 0.01 секунду
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException exc) {
                            System.out.println(exc.toString());
                        }

                        //Выбираем нормальную, полную строку
                        if (data.matches("X:-?\\d{5,6}\\sY:-?\\d{5,6}\\s*Z:-?\\d{5,6}\\s*")) {
                            String[] cords = data.split("\\s");

                            //Получаем изменение положения из строки (x и y - оси изменены местами, у числа убирается разряд единиц).
                            xi = Integer.parseInt(cords[1].substring(2, cords[1].length() - 1));
                            yi = Integer.parseInt(cords[0].substring(2, cords[0].length() - 1));
                            zi = Integer.parseInt(cords[2].substring(2, cords[2].length() - 1));

                            //Смещаем x, y по полученным числам, если x, y меньше нуля - приравнять к нулю, если больше размера экрана - приравнять к размеру экрана
                            x -= xi;
                            x = Math.max(0, x);
                            x = Math.min(sizeX, x);
                            y -= yi;
                            y = Math.max(0, y);
                            y = Math.min(sizeY, y);

                            //Для z - меняем цвет
                            if (Math.abs(zi) > 50) {
                                c = Color.getHSBColor((float)Math.random() * 255, (float)Math.random() * 255, (float)Math.random() * 255);
                            }

                            //System.out.println("Координаты точки: x = " + x + " y = " + y);
                            repaint(); //Рисуем (метод paintComponent())
                            dataError = false;
                        } else { //Попытка восстановить правильность строки, прочитав n байт до '\n'
                            int index = data.indexOf("\n");
                            if (index >= 0) {
                                data = serialPort.readString(data.indexOf("\n") + 1);
                            }
                            dataError = true; //Служит в качестве счетчика ошибочных строк. Если они повторяются множество раз - цикл необходимо прекратить
                        }
                    } catch (SerialPortException | ArrayIndexOutOfBoundsException | PatternSyntaxException exc) {
                        System.out.println(exc.toString());
                    }
                }
            };

            //Связываемся с портом, открываем его и передаем фрейм и лямбда-выражение
            findAndSetPort(eventListener);

            //Проверяем, удалось ли реализовать соединение (Если объект равен null - генерируем исключение)
            if (serialPort == null) {
                throw new NullPointerException();
            }

            //Цикл проверки - работает ли порт и не выдает ли неправильные координаты
            int count = 0;
            while((serialPort.getLinesStatus())[0] != 0) { //Пока устройство не отключено от питания

                if (dataError) { //Если данные считываются неправильно
                    count++;
                    if (count > 500) { //В случае 500 ошибок (около 5 секунд), идущих подряд - выбрасывается исключение
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

        //Ошибка с пакетом SLF4J решена!
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
