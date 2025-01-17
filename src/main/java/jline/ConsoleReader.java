/*
 * Copyright (c) 2002-2007, Marc Prud'hommeaux. All rights reserved.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 */
package jline;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.ActionListener;

import java.io.*;
import java.util.*;
import java.util.List;

/**
 * A reader for console applications. It supports custom tab-completion,
 * saveable command history, and command line editing. On some platforms,
 * platform-specific commands will need to be issued before the reader will
 * function properly. See {@link Terminal#initializeTerminal} for convenience
 * methods for issuing platform-specific setup commands.
 *
 * @author <a href="mailto:mwp1@cornell.edu">Marc Prud'hommeaux</a>
 */
public class ConsoleReader implements ConsoleOperations {

    final static int TAB_WIDTH = 4;
    String prompt;
    private boolean useHistory = true;
    private boolean usePagination = false;
    public static final String CR = System.getProperty("line.separator");
    private static ResourceBundle loc = ResourceBundle.getBundle(CandidateListCompletionHandler.class.getName());
    // The edit states
    private final int NORMAL = 1;
    private final int SEARCH = 2;
    private final int RETURN_BUFFER = 3;
    private final int RETURN_NULL = 4;
    /**
     * Map that contains the operation name to keymay operation mapping.
     */
    public static SortedMap KEYMAP_NAMES;

    static {
        Map names = new TreeMap();

        names.put("MOVE_TO_BEG", new Integer(MOVE_TO_BEG));
        names.put("MOVE_TO_END", new Integer(MOVE_TO_END));
        names.put("PREV_CHAR", new Integer(PREV_CHAR));
        names.put("NEWLINE", new Integer(NEWLINE));
        names.put("KILL_LINE", new Integer(KILL_LINE));
        names.put("PASTE", new Integer(PASTE));
        names.put("CLEAR_SCREEN", new Integer(CLEAR_SCREEN));
        names.put("NEXT_HISTORY", new Integer(NEXT_HISTORY));
        names.put("PREV_HISTORY", new Integer(PREV_HISTORY));
        names.put("START_OF_HISTORY", new Integer(START_OF_HISTORY));
        names.put("END_OF_HISTORY", new Integer(END_OF_HISTORY));
        names.put("REDISPLAY", new Integer(REDISPLAY));
        names.put("KILL_LINE_PREV", new Integer(KILL_LINE_PREV));
        names.put("DELETE_PREV_WORD", new Integer(DELETE_PREV_WORD));
        names.put("NEXT_CHAR", new Integer(NEXT_CHAR));
        names.put("REPEAT_PREV_CHAR", new Integer(REPEAT_PREV_CHAR));
        names.put("SEARCH_PREV", new Integer(SEARCH_PREV));
        names.put("REPEAT_NEXT_CHAR", new Integer(REPEAT_NEXT_CHAR));
        names.put("SEARCH_NEXT", new Integer(SEARCH_NEXT));
        names.put("PREV_SPACE_WORD", new Integer(PREV_SPACE_WORD));
        names.put("TO_END_WORD", new Integer(TO_END_WORD));
        names.put("REPEAT_SEARCH_PREV", new Integer(REPEAT_SEARCH_PREV));
        names.put("PASTE_PREV", new Integer(PASTE_PREV));
        names.put("REPLACE_MODE", new Integer(REPLACE_MODE));
        names.put("SUBSTITUTE_LINE", new Integer(SUBSTITUTE_LINE));
        names.put("TO_PREV_CHAR", new Integer(TO_PREV_CHAR));
        names.put("NEXT_SPACE_WORD", new Integer(NEXT_SPACE_WORD));
        names.put("DELETE_PREV_CHAR", new Integer(DELETE_PREV_CHAR));
        names.put("ADD", new Integer(ADD));
        names.put("PREV_WORD", new Integer(PREV_WORD));
        names.put("CHANGE_META", new Integer(CHANGE_META));
        names.put("DELETE_META", new Integer(DELETE_META));
        names.put("END_WORD", new Integer(END_WORD));
        names.put("NEXT_CHAR", new Integer(NEXT_CHAR));
        names.put("INSERT", new Integer(INSERT));
        names.put("REPEAT_SEARCH_NEXT", new Integer(REPEAT_SEARCH_NEXT));
        names.put("PASTE_NEXT", new Integer(PASTE_NEXT));
        names.put("REPLACE_CHAR", new Integer(REPLACE_CHAR));
        names.put("SUBSTITUTE_CHAR", new Integer(SUBSTITUTE_CHAR));
        names.put("TO_NEXT_CHAR", new Integer(TO_NEXT_CHAR));
        names.put("UNDO", new Integer(UNDO));
        names.put("NEXT_WORD", new Integer(NEXT_WORD));
        names.put("DELETE_NEXT_CHAR", new Integer(DELETE_NEXT_CHAR));
        names.put("CHANGE_CASE", new Integer(CHANGE_CASE));
        names.put("COMPLETE", new Integer(COMPLETE));
        names.put("EXIT", new Integer(EXIT));
        names.put("CLEAR_LINE", new Integer(CLEAR_LINE));
        names.put("ABORT", new Integer(ABORT));
        names.put("TRANSPOSE", new Integer(TRANSPOSE));

        KEYMAP_NAMES = new TreeMap(Collections.unmodifiableMap(names));
    }
    /**
     * The map for logical operations.
     */
    private final Keybindings keybindings;
    /**
     * If true, issue an audible keyboard bell when appropriate.
     */
    private boolean bellEnabled = true;
    /**
     * The current character mask.
     */
    private Character mask = null;
    /**
     * The null mask.
     */
    private static final Character NULL_MASK = new Character((char) 0);
    /**
     * The number of tab-completion candidates above which a warning will be
     * prompted before showing all the candidates.
     */
    private int autoprintThreshhold = Integer.getInteger(
            "jline.completion.threshold", 100).intValue(); // same default as
    // bash
    /**
     * The Terminal to use.
     */
    private final Terminal terminal;
    private CompletionHandler completionHandler = new CandidateListCompletionHandler();
    InputStream in;
    final Writer out;
    final CursorBuffer buf = new CursorBuffer();
    static PrintWriter debugger;
    History history = new History();
    final List completors = new LinkedList();
    private Character echoCharacter = null;
    private Map triggeredActions = new HashMap();
    private StringBuffer searchTerm = null;
    private String previousSearchTerm = "";
    private int searchIndex = -1;

    /**
     * Adding a triggered Action allows to give another course of action
     * if a character passed the preprocessing.
     *
     * Say you want to close the application if the user enter q.
     * addTriggerAction('q', new ActionListener(){ System.exit(0); });
     * would do the trick.
     *
     * @param c
     * @param listener
     */
    public void addTriggeredAction(char c, ActionListener listener) {
        triggeredActions.put(new Character(c), listener);
    }

    /**
     * Create a new reader using {@link FileDescriptor#in} for input and
     * {@link System#out} for output. {@link FileDescriptor#in} is used because
     * it has a better chance of being unbuffered.
     */
    public ConsoleReader() throws IOException {
        this(new FileInputStream(FileDescriptor.in),
                new PrintWriter(
                new OutputStreamWriter(System.out,
                System.getProperty("jline.WindowsTerminal.output.encoding", System.getProperty("file.encoding")))));
    }

    /**
     * Create a new reader using the specified {@link InputStream} for input and
     * the specific writer for output, using the default keybindings resource.
     */
    public ConsoleReader(final InputStream in, final Writer out)
            throws IOException {
        this(in, out, null);
    }

    public ConsoleReader(final InputStream in, final Writer out,
            final InputStream bindings) throws IOException {
        this(in, out, bindings, Terminal.getTerminal());
    }

    /**
     * Create a new reader.
     *
     * @param in
     *            the input
     * @param out
     *            the output
     * @param bindings
     *            the key bindings to use
     * @param term
     *            the terminal to use
     */
    public ConsoleReader(InputStream in, Writer out, InputStream bindings,
            Terminal term) throws IOException {
        this.terminal = term;
        setInput(in);
        this.out = out;
        if (bindings == null) {
            try {
                String bindingFile = System.getProperty("jline.keybindings",
                        new File(System.getProperty("user.home",
                        ".jlinebindings.properties")).getAbsolutePath());

                if (new File(bindingFile).isFile()) {
                    bindings = new FileInputStream(new File(bindingFile));
                }
            } catch (Exception e) {
                // swallow exceptions with option debugging
                if (debugger != null) {
                    e.printStackTrace(debugger);
                }
            }
        }

        if (bindings == null) {
            bindings = terminal.getDefaultBindings();
        }

        this.keybindings = new Keybindings();

        /**
         * Loads the key bindings. Bindings file is in the format:
         *
         * keycode: operation name
         */
        if (bindings != null) {
            Properties p = new Properties();
            p.load(bindings);
            bindings.close();

            for (Iterator i = p.keySet().iterator(); i.hasNext();) {
                String val = (String) i.next();

                try {
                    int code = Keybindings.parseKey(val);
                    String op = (String) p.getProperty(val);

                    Integer opval = (Integer) KEYMAP_NAMES.get(op);

                    if (debugger != null)
                        debugger.println("Mapping " + val + " (code: " + code + ") to " + op + " (code: " + opval + ")");

                    if (code != -1 && opval != null) {
                        keybindings.bindKey(code, opval.intValue());
                    } else {
                        System.out.println("Warning: unknown opval '" + op + "' in bindings definition.");
                    }
                } catch (NumberFormatException nfe) {
                    consumeException(nfe);
                }
            }
        }
    }

    public Terminal getTerminal() {
        return this.terminal;
    }

    /**
     * Set the stream for debugging. Development use only.
     */
    public void setDebug(final PrintWriter debugger) {
        ConsoleReader.debugger = debugger;
    }

    /**
     * Set the stream to be used for console input.
     */
    public void setInput(final InputStream in) {
        this.in = in;
    }

    /**
     * Returns the stream used for console input.
     */
    public InputStream getInput() {
        return this.in;
    }

    /**
     * Read the next line and return the contents of the buffer.
     */
    public String readLine() throws IOException {
        return readLine((String) null);
    }

    /**
     * Read the next line with the specified character mask. If null, then
     * characters will be echoed. If 0, then no characters will be echoed.
     */
    public String readLine(final Character mask) throws IOException {
        return readLine(null, mask);
    }

    /**
     * @param bellEnabled
     *            if true, enable audible keyboard bells if an alert is
     *            required.
     */
    public void setBellEnabled(final boolean bellEnabled) {
        this.bellEnabled = bellEnabled;
    }

    /**
     * @return true is audible keyboard bell is enabled.
     */
    public boolean getBellEnabled() {
        return this.bellEnabled;
    }

    /**
     * Query the terminal to find the current width;
     *
     * @see Terminal#getTerminalWidth
     * @return the width of the current terminal.
     */
    public int getTermwidth() {
        return getTerminal().getTerminalWidth();
    }

    /**
     * Query the terminal to find the current width;
     *
     * @see Terminal#getTerminalHeight
     *
     * @return the height of the current terminal.
     */
    public int getTermheight() {
        return getTerminal().getTerminalHeight();
    }

    /**
     * @param autoprintThreshhold
     *            the number of candidates to print without issuing a warning.
     */
    public void setAutoprintThreshhold(final int autoprintThreshhold) {
        this.autoprintThreshhold = autoprintThreshhold;
    }

    /**
     * @return the number of candidates to print without issing a warning.
     */
    public int getAutoprintThreshhold() {
        return this.autoprintThreshhold;
    }

    /** Look up the virtual key code for an action. */
    int getVirtualKeyForAction(short logicalAction) {
        return keybindings.getKeyForAction(logicalAction);
    }

    /**
     * Look up the key sequence for a virtual key.
     *
     * Naturally, this depends on the used terminal and operating system.
     */
    String getKeyForVirtualKey(int virtualKey) {
        return terminal.getKeyForVirtualKey(virtualKey);
    }

    /**
     * Clear the echoed characters for the specified character code.
     */
    int clearEcho(int c) throws IOException {
        // if the terminal is not echoing, then just return...
        if (!terminal.getEcho()) {
            return 0;
        }

        // otherwise, clear
        int num = countEchoCharacters((char) c);
        back(num);
        drawBuffer(num);

        return num;
    }

    int countEchoCharacters(char c) {
        // tabs as special: we need to determine the number of spaces
        // to cancel based on what out current cursor position is
        if (c == 9) {
            int tabstop = 8; // will this ever be different?
            int position = getCursorPosition();

            return tabstop - (position % tabstop);
        }

        return getPrintableCharacters(c).length();
    }

    /**
     * Return the number of characters that will be printed when the specified
     * character is echoed to the screen. Adapted from cat by Torbjorn Granlund,
     * as repeated in stty by David MacKenzie.
     */
    StringBuffer getPrintableCharacters(char ch) {
        StringBuffer sbuff = new StringBuffer();

        if (ch >= 32) {
            if (ch < 127) {
                sbuff.append(ch);
            } else if (ch == 127) {
                sbuff.append('^');
                sbuff.append('?');
            } else {
                sbuff.append('M');
                sbuff.append('-');

                if (ch >= (128 + 32)) {
                    if (ch < (128 + 127)) {
                        sbuff.append((char) (ch - 128));
                    } else {
                        sbuff.append('^');
                        sbuff.append('?');
                    }
                } else {
                    sbuff.append('^');
                    sbuff.append((char) (ch - 128 + 64));
                }
            }
        } else {
            sbuff.append('^');
            sbuff.append((char) (ch + 64));
        }

        return sbuff;
    }

    int getCursorPosition() {
        // FIXME: does not handle anything but a line with a prompt
        // absolute position
        return ((prompt == null) ? 0 : prompt.length()) + buf.getCursor();
    }

    public String readLine(final String prompt) throws IOException {
        return readLine(prompt, null);
    }

    /**
     * The default prompt that will be issued.
     */
    public void setDefaultPrompt(String prompt) {
        this.prompt = prompt;
    }

    /**
     * The default prompt that will be issued.
     */
    public String getDefaultPrompt() {
        return prompt;
    }
    private int state;

    /**
     * Read a line from the <i>in</i> {@link InputStream}, and return the line
     * (without any trailing newlines).
     *
     * @param prompt
     *            the prompt to issue to the console, may be null.
     * @return a line that is read from the terminal, or null if there was null
     *         input (e.g., <i>CTRL-D</i> was pressed).
     */
    public String readLine(final String prompt, final Character mask)
            throws IOException {
        this.mask = mask;
        if (prompt != null) {
            this.prompt = prompt;
        }

        try {
            terminal.beforeReadLine(this, this.prompt, mask);

            if ((this.prompt != null) && (this.prompt.length() > 0)) {
                out.write(this.prompt);
                out.flush();
            }

            // if the terminal is unsupported, just use plain-java reading
            if (!terminal.isSupported()) {
                return readLine(in);
            } else {
                state = NORMAL;

                while (state != RETURN_NULL && state != RETURN_BUFFER) {
                    // Read next key and look up the command binding.
                    int c = readVirtualKey();

                    if (c == -1) { // EOF
                        return null;
                    }

                    int code = readBinding(c);

                    // First, check for search mode.
                    if (state == SEARCH) {
                        processKeySearchMode(code, c);
                    }

                    // Search mode might set the state to NORMAL, then we
                    // have to reprocess the character.
                    if (state == NORMAL) {
                        processKeyNormalMode(code, c);
                    }
                }

                // Check for possible exit options
                if (state == RETURN_NULL) {
                    return null;
                } else if (state == RETURN_BUFFER) {
                    return finishBuffer();
                } else {
                    throw new RuntimeException("Readline ended up in unknown state.");
                }
            }
        } finally {
            terminal.afterReadLine(this, this.prompt, mask);
        }
    }

    /** Cause readLine to return.
     *
     *  To be used by a registered ActionListener.
     *
     *  @param clear  Set to true if the current buffer should be cleared.
     */

    public void exitReadLine(boolean clear) {
      try {
	moveToEnd();
	printNewline();
      }
      catch (IOException ignored) {}

      if (clear) {
	buf.setLength(0);
      }

      state = RETURN_BUFFER;
    }

    /** Process a key in normal mode. */
    private void processKeyNormalMode(int code, int c) throws IOException {
        boolean success = true;
        switch (code) {
            case EXIT: // ctrl-d

                if (buf.isEmpty()) {
                    state = RETURN_NULL;
                    return;
                } else {
                    success = deleteCurrentCharacter();
                }
                break;

            case COMPLETE: // tab
                success = complete();
                break;

            case MOVE_TO_BEG:
                success = setCursorPosition(0);
                break;

            case KILL_LINE: // CTRL-K
                success = killLine();
                break;

            case CLEAR_SCREEN: // CTRL-L
                success = clearScreen();
                break;

            case KILL_LINE_PREV: // CTRL-U
                success = resetLine();
                break;

            case NEWLINE: // enter
                moveToEnd();
                printNewline(); // output newline
                state = RETURN_BUFFER;
                return;

            case DELETE_PREV_CHAR: // backspace
                success = backspace();
                break;

            case DELETE_NEXT_CHAR: // delete
                success = deleteCurrentCharacter();
                break;

            case MOVE_TO_END:
                success = moveToEnd();
                break;

            case PREV_CHAR:
                success = moveCursor(-1) != 0;
                break;

            case NEXT_CHAR:
                success = moveCursor(1) != 0;
                break;

            case NEXT_HISTORY:
                success = moveHistory(true);
                break;

            case PREV_HISTORY:
                success = moveHistory(false);
                break;

            case REDISPLAY:
                break;

            case PASTE:
                success = paste();
                break;

            case DELETE_PREV_WORD:
                success = deletePreviousWord();
                break;

            case PREV_WORD:
                success = previousWord();
                break;

            case NEXT_WORD:
                success = nextWord();
                break;

            case START_OF_HISTORY:
                success = history.moveToFirstEntry();
                if (success) {
                    setBuffer(history.current());
                }
                break;

            case END_OF_HISTORY:
                success = history.moveToLastEntry();
                if (success) {
                    setBuffer(history.current());
                }
                break;

            case CLEAR_LINE:
                moveInternal(-(buf.length()));
                killLine();
                break;

            case INSERT:
                buf.setOvertyping(!buf.isOvertyping());
                break;

            case SEARCH_PREV: // CTRL-R
                if (searchTerm != null) {
                    previousSearchTerm = searchTerm.toString();
                }
                searchTerm = new StringBuffer();
                searchIndex = -1;
                state = SEARCH;
                printSearchStatus("", "");
                break;

            case UNKNOWN:
                if (c != 0) { // ignore null chars
                    ActionListener action = (ActionListener) triggeredActions.get(new Character((char) c));
                    if (action != null) {
                        action.actionPerformed(null);
                    }
                } else {
                    success = false;
                }
                break;

            case TRANSPOSE:
                transpose();
                success = true;
                break;

            default:
                putChar(c, true);
                success = true;
                break;
        }

        if (!(success)) {
            beep();
        }

        flushConsole();
    }

    /** Process a key in search mode. */
    private void processKeySearchMode(int code, int c) throws IOException {
        switch (code) {
            case ABORT:
                state = NORMAL;
                break;
                
            case SEARCH_PREV:
                // If the search term is empty, go to the previous search term.
                if (searchTerm.length() == 0) {
                    System.out.println("Filling in old search term '" + previousSearchTerm + "'");
                    searchTerm.append(previousSearchTerm);
                    searchIndex = history.searchBackwards(searchTerm.toString());
                } else {
                    if (searchIndex != -1)
                        searchIndex = history.searchBackwards(searchTerm.toString(), searchIndex);
                }
                break;
            case DELETE_PREV_CHAR:
                if (searchTerm.length() > 0) {
                    searchTerm.deleteCharAt(searchTerm.length() - 1);
                    searchIndex = history.searchBackwards(searchTerm.toString());
                }
                break;
            default:
                if (code > 0) {
                    searchTerm.appendCodePoint(c);
                    searchIndex = history.searchBackwards(searchTerm.toString());
                    break;
                } else {
                    // Set buffer and cursor position to the found string, and go back to normal mode.
                    if (searchIndex != -1) {
                        history.setCurrentIndex(searchIndex);
                        setBuffer(history.current());
                        buf.setCursor(history.current().indexOf(searchTerm.toString()));
                    }
                    state = NORMAL;
                    break;
                }
        }
        
        // if we're still in search mode, print the search status
        if (state == SEARCH) {
            if (searchTerm.length() == 0) {
                printSearchStatus("", "");
            } else {
                if (searchIndex == -1) {
                    beep();
                } else {
                    printSearchStatus(searchTerm.toString(), history.getHistory(searchIndex));
                }
            }
        } else {
            restoreLine();
        }
    }

    private String readLine(InputStream in) throws IOException {
        StringBuffer buf = new StringBuffer();

        while (true) {
            int i = in.read();

            if ((i == -1) || (i == '\n') || (i == '\r')) {
                return buf.toString();
            }

            buf.append((char) i);
        }

        // return new BufferedReader (new InputStreamReader (in)).readLine ();
    }

    /**
     * Reads the console input and returns an array of the form [raw, key
     * binding].
     */
    private int readBinding(int c) throws IOException {
        // extract the appropriate key binding
        int code = keybindings.resolveKey(c);

        if (debugger != null) {
            debug("    translated: " + (int) c + ": " + code);
        }

        return code;
    }

    /**
     * Move up or down the history tree.
     */
    private final boolean moveHistory(final boolean next) throws IOException {
        if (next && !history.next()) {
            return false;
        } else if (!next && !history.previous()) {
            return false;
        }

        setBuffer(history.current());

        return true;
    }

    /**
     * Paste the contents of the clipboard into the console buffer
     *
     * @return true if clipboard contents pasted
     */
    public boolean paste() throws IOException {
        Clipboard clipboard;
        try { // May throw ugly exception on system without X
            clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        } catch (Exception e) {
            return false;
        }

        if (clipboard == null) {
            return false;
        }

        Transferable transferable = clipboard.getContents(null);

        if (transferable == null) {
            return false;
        }

        try {
            Object content = transferable.getTransferData(DataFlavor.plainTextFlavor);

            /*
             * This fix was suggested in bug #1060649 at
             * http://sourceforge.net/tracker/index.php?func=detail&aid=1060649&group_id=64033&atid=506056
             * to get around the deprecated DataFlavor.plainTextFlavor, but it
             * raises a UnsupportedFlavorException on Mac OS X
             */
            if (content == null) {
                try {
                    content = new DataFlavor().getReaderForText(transferable);
                } catch (Exception e) {
                }
            }

            if (content == null) {
                return false;
            }

            String value;

            if (content instanceof Reader) {
                // TODO: we might want instead connect to the input stream
                // so we can interpret individual lines
                value = "";

                String line = null;

                for (BufferedReader read = new BufferedReader((Reader) content); (line = read.readLine()) != null;) {
                    if (value.length() > 0) {
                        value += "\n";
                    }

                    value += line;
                }
            } else {
                value = content.toString();
            }

            if (value == null) {
                return true;
            }

            putString(value);

            return true;
        } catch (UnsupportedFlavorException ufe) {
            if (debugger != null) {
                debug(ufe + "");
            }

            return false;
        }
    }

    /**
     * Kill the buffer ahead of the current cursor position.
     *
     * @return true if successful
     */
    public boolean killLine() throws IOException {
        int cp = buf.getCursor();
        int len = buf.length();

        if (cp >= len) {
            return false;
        }

        int num = buf.length() - cp;
        clearAhead(num);

        for (int i = 0; i < num; i++) {
            buf.deleteCharAt(len - i - 1);
        }

        return true;
    }

    /**
     * Clear the screen by issuing the ANSI "clear screen" code.
     */
    public boolean clearScreen() throws IOException {
        if (!terminal.supportsCommand(Terminal.CMD_CLEAR_SCREEN)
            || !terminal.supportsCommand(Terminal.CMD_HOME)) {
            return false;
        }

        // send the ANSI code to clear the screen
        printString(terminal.getCommand(Terminal.CMD_CLEAR_SCREEN));
        flushConsole();

        // then send the ANSI code to go to position 1,1
        printString(terminal.getCommand(Terminal.CMD_HOME));
        flushConsole();

        redrawLine();

        return true;
    }

    /**
     * Use the completors to modify the buffer with the appropriate completions.
     *
     * @return true if successful
     */
    private final boolean complete() throws IOException {
        // debug ("tab for (" + buf + ")");
        if (completors.size() == 0) {
            return false;
        }

        List candidates = new LinkedList();
        String bufstr = buf.toString();
        int cursor = buf.getCursor();

        int position = -1;

        for (Iterator i = completors.iterator(); i.hasNext();) {
            Completor comp = (Completor) i.next();

            if ((position = comp.complete(bufstr, cursor, candidates)) != -1) {
                break;
            }
        }

        // no candidates? Fail.
        if (candidates.size() == 0) {
            return false;
        }

        return completionHandler.complete(this, candidates, position);
    }

    public CursorBuffer getCursorBuffer() {
        return buf;
    }

    /**
     * Output the specified {@link Collection} in proper columns.
     *
     * @param stuff
     *            the stuff to print
     */
    public void printColumns(final Collection stuff) throws IOException {
        if ((stuff == null) || (stuff.size() == 0)) {
            return;
        }

        int width = getTermwidth();
        int maxwidth = 0;

        for (Iterator i = stuff.iterator(); i.hasNext(); maxwidth = Math.max(
                        maxwidth, i.next().toString().length())) {
            ;
        }

        StringBuffer line = new StringBuffer();

        int showLines;

        if (usePagination) {
            showLines = getTermheight() - 1; // page limit
        } else {
            showLines = Integer.MAX_VALUE;
        }

        for (Iterator i = stuff.iterator(); i.hasNext();) {
            String cur = (String) i.next();

            if ((line.length() + maxwidth) > width) {
                printString(line.toString().trim());
                printNewline();
                line.setLength(0);
                if (--showLines == 0) { // Overflow
                    printString(loc.getString("display-more"));
                    flushConsole();
                    int c = readVirtualKey();
                    if (c == '\r' || c == '\n') {
                        showLines = 1; // one step forward
                    } else if (c != 'q') {
                        showLines = getTermheight() - 1; // page forward
                    }
                    back(loc.getString("display-more").length());
                    if (c == 'q') {
                        break; // cancel
                    }
                }
            }

            pad(cur, maxwidth + 3, line);
        }

        if (line.length() > 0) {
            printString(line.toString().trim());
            printNewline();
            line.setLength(0);
        }
    }

    /**
     * Append <i>toPad</i> to the specified <i>appendTo</i>, as well as (<i>toPad.length () -
     * len</i>) spaces.
     *
     * @param toPad
     *            the {@link String} to pad
     * @param len
     *            the target length
     * @param appendTo
     *            the {@link StringBuffer} to which to append the padded
     *            {@link String}.
     */
    private final void pad(final String toPad, final int len,
            final StringBuffer appendTo) {
        appendTo.append(toPad);

        for (int i = 0; i < (len - toPad.length()); i++, appendTo.append(' ')) {
            ;
        }
    }

    /**
     * Add the specified {@link Completor} to the list of handlers for
     * tab-completion.
     *
     * @param completor
     *            the {@link Completor} to add
     * @return true if it was successfully added
     */
    public boolean addCompletor(final Completor completor) {
        return completors.add(completor);
    }

    /**
     * Remove the specified {@link Completor} from the list of handlers for
     * tab-completion.
     *
     * @param completor
     *            the {@link Completor} to remove
     * @return true if it was successfully removed
     */
    public boolean removeCompletor(final Completor completor) {
        return completors.remove(completor);
    }

    /**
     * Returns an unmodifiable list of all the completors.
     */
    public Collection getCompletors() {
        return Collections.unmodifiableList(completors);
    }

    /**
     * Erase the current line.
     *
     * @return false if we failed (e.g., the buffer was empty)
     */
    final boolean resetLine() throws IOException {
        if (buf.isAtStart()) {
            return false;
        }

        backspaceAll();

        return true;
    }

    /**
     * Move the cursor position to the specified absolute index.
     */
    public final boolean setCursorPosition(final int position)
            throws IOException {
        return moveCursor(position - buf.getCursor()) != 0;
    }

    /**
     * Set the current buffer's content to the specified {@link String}. The
     * visual console will be modified to show the current buffer.
     *
     * @param buffer
     *            the new contents of the buffer.
     */
    private final void setBuffer(final String buffer) throws IOException {
        // don't bother modifying it if it is unchanged
        if (buffer.equals(buf.toString())) {
            return;
        }

        // obtain the difference between the current buffer and the new one
        int sameIndex = 0;

        for (int i = 0, l1 = buffer.length(), l2 = buf.length(); (i < l1) && (i < l2); i++) {
            if (buffer.charAt(i) == buf.charAt(i)) {
                sameIndex++;
            } else {
                break;
            }
        }

        int diff = buf.length() - sameIndex;

        backspace(diff); // go back for the differences
        killLine(); // clear to the end of the line
        buf.setLength(sameIndex); // the new length
        putString(buffer.substring(sameIndex)); // append the differences
    }

    /**
     * Clear the line and redraw it.
     */
    public final void redrawLine() throws IOException {
        printCharacter(RESET_LINE);
        flushConsole();
        drawLine();
    }

    /**
     * Output put the prompt + the current buffer
     */
    public final void drawLine() throws IOException {
        if (prompt != null) {
            printString(prompt);
        }

        printString(buf.toString());

        if (buf.length() != buf.getCursor()) // not at end of line
        {
            back(buf.length() - buf.getCursor()); // sync
        }
    }

    /**
     * Output a platform-dependant newline.
     */
    public final void printNewline() throws IOException {
        printString(CR);
        flushConsole();
    }

    /**
     * Clear the buffer and add its contents to the history.
     *
     * @return the former contents of the buffer.
     */
    final String finishBuffer() {
        String str = buf.toString();

        // we only add it to the history if the buffer is not empty
        // and if mask is null, since having a mask typically means
        // the string was a password. We clear the mask after this call
        if (str.length() > 0) {
            if (mask == null && useHistory) {
                history.addToHistory(str);
            } else {
                mask = null;
            }
        }

        history.moveToEnd();

        buf.setLength(0);
        buf.setCursor(0);

        return str;
    }

    /**
     * Write out the specified string to the buffer and the output stream.
     */
    public final void putString(final String str) throws IOException {
        buf.write(str);
        printString(str);
        drawBuffer();
    }

    /**
     * Output the specified string to the output stream (but not the buffer).
     */
    public final void printString(final String str) throws IOException {
        printCharacters(str.toCharArray());
    }

    /**
     * Output the specified character, both to the buffer and the output stream.
     */
    private final void putChar(final int c, final boolean print)
            throws IOException {
        buf.write((char) c);

        if (print) {
            // no masking...
            if (mask == null) {
                printCharacter(c);
            } // null mask: don't print anything...
            else if (mask.charValue() == 0) {
                ;
            } // otherwise print the mask...
            else {
                printCharacter(mask.charValue());
            }

            drawBuffer();
        }
    }

    /**
     * Redraw the rest of the buffer from the cursor onwards. This is necessary
     * for inserting text into the buffer.
     *
     * @param clear
     *            the number of characters to clear after the end of the buffer
     */
    private final void drawBuffer(final int clear) throws IOException {
        // debug ("drawBuffer: " + clear);
        char[] chars = buf.substring(buf.getCursor()).toCharArray();
        if (mask != null) {
            Arrays.fill(chars, mask.charValue());
        }

        printCharacters(chars);

        clearAhead(clear);
        back(chars.length);
        flushConsole();
    }

    /**
     * Redraw the rest of the buffer from the cursor onwards. This is necessary
     * for inserting text into the buffer.
     */
    private final void drawBuffer() throws IOException {
        drawBuffer(0);
    }

    /**
     * Clear ahead the specified number of characters without moving the cursor.
     */
    private final void clearAhead(final int num) throws IOException {
        if (num == 0) {
            return;
        }

        // debug ("clearAhead: " + num);

        // print blank extra characters
        printCharacters(' ', num);

        // we need to flush here so a "clever" console
        // doesn't just ignore the redundancy of a space followed by
        // a backspace.
        flushConsole();

        // reset the visual cursor
        back(num);

        flushConsole();
    }

    /**
     * Move the visual cursor backwards without modifying the buffer cursor.
     */
    private final void back(final int num) throws IOException {
        printCharacters(BACKSPACE, num);
        flushConsole();
    }

    /**
     * Issue an audible keyboard bell, if {@link #getBellEnabled} return true.
     */
    public final void beep() throws IOException {
        if (!(getBellEnabled())) {
            return;
        }

        printCharacter(KEYBOARD_BELL);
        // need to flush so the console actually beeps
        flushConsole();
    }

    /**
     * Output the specified character to the output stream without manipulating
     * the current buffer.
     */
    private final void printCharacter(final int c) throws IOException {
        if (c == '\t') {
            char cbuf[] = new char[TAB_WIDTH];
            Arrays.fill(cbuf, ' ');
            out.write(cbuf);
            return;
        }

        out.write(c);
    }

    /**
     * Output the specified characters to the output stream without manipulating
     * the current buffer.
     */
    private final void printCharacters(final char[] c) throws IOException {
        int len = 0;
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '\t') {
                len += TAB_WIDTH;
            } else {
                len++;
            }
        }

        char cbuf[];
        if (len == c.length) {
            cbuf = c;
        } else {
            cbuf = new char[len];
            int pos = 0;
            for (int i = 0; i < c.length; i++) {
                if (c[i] == '\t') {
                    Arrays.fill(cbuf, pos, pos + TAB_WIDTH, ' ');
                    pos += TAB_WIDTH;
                } else {
                    cbuf[pos] = c[i];
                    pos++;
                }
            }
        }

        out.write(cbuf);
    }

    private final void printCharacters(final char c, final int num)
            throws IOException {
        if (num == 1) {
            printCharacter(c);
        } else {
            char[] chars = new char[num];
            Arrays.fill(chars, c);
            printCharacters(chars);
        }
    }

    /**
     * Flush the console output stream. This is important for printout out
     * single characters (like a backspace or keyboard) that we want the console
     * to handle immedately.
     */
    public final void flushConsole() throws IOException {
        out.flush();
    }

    private final int backspaceAll() throws IOException {
        return backspace(Integer.MAX_VALUE);
    }

    /**
     * Issue <em>num</em> backspaces.
     *
     * @return the number of characters backed up
     */
    private final int backspace(final int num) throws IOException {
        if (buf.isAtStart()) {
            return 0;
        }

        int count = 0;

        count = moveCursor(-1 * num) * -1;
        // debug ("Deleting from " + buf.cursor + " for " + count);
        buf.delete(buf.getCursor(), buf.getCursor() + count);
        drawBuffer(count);

        return count;
    }

    /**
     * Issue a backspace.
     *
     * @return true if successful
     */
    public final boolean backspace() throws IOException {
        return backspace(1) == 1;
    }

    private final boolean moveToEnd() throws IOException {
        if (moveCursor(1) == 0) {
            return false;
        }

        while (moveCursor(1) != 0) {
            ;
        }

        return true;
    }

    /**
     * Delete the character at the current position and redraw the remainder of
     * the buffer.
     */
    private final boolean deleteCurrentCharacter() throws IOException {
        boolean success = buf.length() > 0;
        if (!success) {
            return false;
        }

        if (buf.isAtEnd()) {
            return false;
        }

        buf.deleteCharAt(buf.getCursor());
        drawBuffer(1);
        return true;
    }

    private final boolean previousWord() throws IOException {
        while (isDelimiter(buf.current()) && (moveCursor(-1) != 0)) {
            ;
        }

        while (!isDelimiter(buf.current()) && (moveCursor(-1) != 0)) {
            ;
        }

        return true;
    }

    private final boolean nextWord() throws IOException {
        while (isDelimiter(buf.current()) && (moveCursor(1) != 0)) {
            ;
        }

        while (!isDelimiter(buf.current()) && (moveCursor(1) != 0)) {
            ;
        }

        return true;
    }

    private final boolean deletePreviousWord() throws IOException {
        while (isDelimiter(buf.current()) && backspace()) {
            ;
        }

        while (!isDelimiter(buf.current()) && backspace()) {
            ;
        }

        return true;
    }

    /**
     * Move the cursor <i>where</i> characters.
     *
     * @param where
     *            if less than 0, move abs(<i>where</i>) to the left,
     *            otherwise move <i>where</i> to the right.
     *
     * @return the number of spaces we moved
     */
    public final int moveCursor(final int num) throws IOException {
        int where = num;

        if (buf.isAtStart() && (where < 0)) {
            return 0;
        }

        if (buf.isAtEnd() && (where > 0)) {
            return 0;
        }

        if ((buf.getCursor() + where) < 0) {
            where = -buf.getCursor();
        } else if ((buf.getCursor() + where) > buf.length()) {
            where = buf.length() - buf.getCursor();
        }

        moveInternal(where);

        return where;
    }

    /**
     * debug.
     *
     * @param str
     *            the message to issue.
     */
    public static void debug(final String str) {
        if (debugger != null) {
            debugger.println(str);
            debugger.flush();
        }
    }

    /**
     * Move the cursor <i>where</i> characters, withough checking the current
     * buffer.
     *
     * @see #where
     *
     * @param where
     *            the number of characters to move to the right or left.
     */
    private final void moveInternal(final int where) throws IOException {
        // debug ("move cursor " + where + " ("
        // + buf.cursor + " => " + (buf.cursor + where) + ")");
        buf.setCursor(buf.getCursor() + where);

        char c;

        if (where < 0) {
            int len = 0;
            for (int i = buf.getCursor(); i < buf.getCursor() - where; i++) {
                if (buf.charAt(i) == '\t') {
                    len += TAB_WIDTH;
                } else {
                    len++;
                }
            }

            char cbuf[] = new char[len];
            Arrays.fill(cbuf, BACKSPACE);
            out.write(cbuf);

            return;
        } else if (buf.isAtStart()) {
            return;
        } else if (mask != null) {
            c = mask.charValue();
        } else {
            printCharacters(buf.substring(buf.getCursor() - where, buf.getCursor()).toCharArray());
            return;
        }

        // null character mask: don't output anything
        if (NULL_MASK.equals(mask)) {
            return;
        }

        printCharacters(c, Math.abs(where));
    }

    /**
     * Read a character from the console.
     *
     * @return the character, or -1 if an EOF is received.
     */
    public final int readVirtualKey() throws IOException {
        int c = terminal.readVirtualKey(in);

        if (debugger != null) {
            debug("keystroke: " + c + "");
        }

        // clear any echo characters
        clearEcho(c);

        return c;
    }

    public final int readCharacter(final char[] allowed) throws IOException {
        // if we restrict to a limited set and the current character
        // is not in the set, then try again.
        char c;

        Arrays.sort(allowed); // always need to sort before binarySearch

        while (Arrays.binarySearch(allowed, c = (char) readVirtualKey()) < 0);

        return c;
    }

    /**
     *  Issue <em>num</em> deletes.
     *
     *  @return  the number of characters backed up
     */
    private final int delete(final int num)
            throws IOException {
        /* Commented out beacuse of DWA-2949:
        if (buf.cursor == 0)
        return 0;*/

        buf.deleteChar();
        drawBuffer(1);

        return 1;
    }

    public final boolean replace(int num, String replacement) {
        buf.replace(buf.getCursor() - num, buf.getCursor(), replacement);
        try {
            moveCursor(-num);
            drawBuffer(Math.max(0, num - replacement.length()));
            moveCursor(replacement.length());
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     *  Issue a delete.
     *
     *  @return  true if successful
     */
    public final boolean delete()
            throws IOException {
        return delete(1) == 1;
    }

    public void setHistory(final History history) {
        this.history = history;
    }

    public History getHistory() {
        return this.history;
    }

    public void setCompletionHandler(final CompletionHandler completionHandler) {
        this.completionHandler = completionHandler;
    }

    public CompletionHandler getCompletionHandler() {
        return this.completionHandler;
    }

    /**
     * <p>
     * Set the echo character. For example, to have "*" entered when a password
     * is typed:
     * </p>
     *
     * <pre>
     * myConsoleReader.setEchoCharacter(new Character('*'));
     * </pre>
     *
     * <p>
     * Setting the character to
     *
     * <pre>
     * null
     * </pre>
     *
     * will restore normal character echoing. Setting the character to
     *
     * <pre>
     * new Character(0)
     * </pre>
     *
     * will cause nothing to be echoed.
     * </p>
     *
     * @param echoCharacter
     *            the character to echo to the console in place of the typed
     *            character.
     */
    public void setEchoCharacter(final Character echoCharacter) {
        this.echoCharacter = echoCharacter;
    }

    /**
     * Returns the echo character.
     */
    public Character getEchoCharacter() {
        return this.echoCharacter;
    }

    /**
     * No-op for exceptions we want to silently consume.
     */
    private void consumeException(final Throwable e) {
    }

    /**
     * Checks to see if the specified character is a delimiter. We consider a
     * character a delimiter if it is anything but a letter or digit.
     *
     * @param c
     *            the character to test
     * @return true if it is a delimiter
     */
    private boolean isDelimiter(char c) {
        return !Character.isLetterOrDigit(c);
    }

    /**
     * Whether or not to add new commands to the history buffer.
     */
    public void setUseHistory(boolean useHistory) {
        this.useHistory = useHistory;
    }

    /**
     * Whether or not to add new commands to the history buffer.
     */
    public boolean getUseHistory() {
        return useHistory;
    }

    /**
     * Whether to use pagination when the number of rows of candidates exceeds
     * the height of the temrinal.
     */
    public void setUsePagination(boolean usePagination) {
        this.usePagination = usePagination;
    }

    /**
     * Whether to use pagination when the number of rows of candidates exceeds
     * the height of the temrinal.
     */
    public boolean getUsePagination() {
        return this.usePagination;
    }

    public void printSearchStatus(String searchTerm, String match) throws IOException {
        int i = match.indexOf(searchTerm);
        printString("\r(reverse-i-search) `" + searchTerm + "': " + match + "\u001b[K");
        back(match.length() - i);
        flushConsole();
    }

    public void restoreLine() throws IOException {
        printString(terminal.getCommand(Terminal.CMD_CLEAR_EOL));
        redrawLine();
        flushConsole();
    }

    private void transpose() throws IOException {
        if (buf.isAtStart())
            return;

        if (buf.length() > 1 && buf.isAtEnd())
            moveCursor(-1);

        moveCursor(-1);
        int c = buf.getChar();
        delete();
        moveCursor(1);
        putChar(c, true);
    }
}
