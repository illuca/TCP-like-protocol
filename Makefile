# Makefile for a Java project with multiple source files
# Define variables
JAVAC = javac
JAVA = java
SRC_DIR = .
BIN_DIR = ./bin
CLASS_DIR = $(BIN_DIR)/classes
MAIN_CLASS_RECEIVER = Receiver
MAIN_CLASS_SENDER = Sender

# Find all .java files in the SRC_DIR and derive the corresponding .class files
JAVA_FILES = $(wildcard $(SRC_DIR)/*.java)
CLASS_FILES = $(patsubst $(SRC_DIR)/%.java,$(CLASS_DIR)/%.class,$(JAVA_FILES))

# Default target
all: $(CLASS_FILES)

# Compile .java files to .class files
$(CLASS_DIR)/%.class: $(SRC_DIR)/%.java | $(CLASS_DIR)
	$(JAVAC) -d $(CLASS_DIR) $<
	

# Create the CLASS_DIR if it doesn't exist
$(CLASS_DIR):
	mkdir -p $(CLASS_DIR)

# Run the Receiver main class
run-receiver: all
	$(JAVA) -cp $(CLASS_DIR) $(MAIN_CLASS_RECEIVER) $(ARGS)

# Run the Sender main class
run-sender: all
	$(JAVA) -cp $(CLASS_DIR) $(MAIN_CLASS_SENDER) $(ARGS)

# Clean up the compiled files
clean:
	rm -rf $(CLASS_DIR)



# Make the clean target a phony target to avoid conflicts with a possible file named 'clean'
.PHONY: clean
