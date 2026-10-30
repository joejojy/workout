"""Create fictional, undated example PDFs without any personal training data."""
from pathlib import Path
from io import BytesIO
import shutil
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader
import pypdfium2 as pdfium

root=Path(__file__).resolve().parents[1]
examples=root/'examples';examples.mkdir(exist_ok=True)
lines=[
    'Workout sample plan',
    'Fictional demonstration data. Not a personalized training recommendation.',
    'Undated example: the app automatically aligns it with the current week.',
    '',
    'MONDAY - Push',
    'Push-ups: 3 x 8-12',
    'Dumbbell overhead press: 3 x 8-10',
    'Plank: 3 x 20-30 sec',
    '',
    'TUESDAY - Easy Run',
    'Distance: 3 km',
    'Keep the effort conversational. Walk when needed.',
    '',
    'WEDNESDAY - Legs',
    'Bodyweight squat: 3 x 10',
    'Step-ups: 2 x 8/side',
    '',
    'THURSDAY - Interval Run',
    '4 x 1 minute comfortable running, with 2 minutes walking between repeats.',
    'Warm up and cool down with easy walking.',
    '',
    'FRIDAY - Pull',
    'Dumbbell row: 3 x 8/side',
    'Band pull-apart: 2 x 12',
    '',
    'SATURDAY - Long Run',
    'Distance: 5 km',
    'Stay at an easy effort; take walking breaks as needed.',
    '',
    'SUNDAY - Rest',
    'Optional gentle walk.',
]
c=canvas.Canvas(str(examples/'sample-plan.pdf'),pagesize=(595,842))
c.setTitle('Workout: fictional sample plan');c.setAuthor('Workout contributors')
y=805
for i,line in enumerate(lines):
    c.setFont('Helvetica-Bold' if i==0 or ' - ' in line else 'Helvetica',16 if i==0 else 10)
    c.drawString(35,y,line);y-=21
c.save()
document=pdfium.PdfDocument(examples/'sample-plan.pdf')
image=document[0].render(scale=2.5).to_pil()
scan=canvas.Canvas(str(examples/'sample-plan-scanned.pdf'),pagesize=(595,842))
scan.setTitle('Workout: fictional scanned example');scan.drawImage(ImageReader(image),0,0,width=595,height=842);scan.showPage();scan.save()
assets=root/'app/src/main/assets';assets.mkdir(parents=True,exist_ok=True)
shutil.copy2(examples/'sample-plan.pdf',assets/'sample-plan.pdf')
print('Created fictional selectable-text and scanned example PDFs.')
