/*
 * python3 del pacchetto di Music Assistant sul telefono: chiama solo Py_BytesMain di libpython3.13.so, che
 * il linker trova attraverso LD_LIBRARY_PATH (la cartella lib del pacchetto). Da Android 10 un'app non può
 * avviare i programmi che ha scaricato: Xonos lo avvia con /system/bin/linker64 python3 <argomenti>.
 */
extern int Py_BytesMain(int argc, char **argv);

int main(int argc, char **argv) {
    return Py_BytesMain(argc, argv);
}
